package com.xiyunmn.qdhelper.feature.bookshield.settings

import android.app.Activity
import android.content.Context
import com.xiyunmn.qdhelper.config.BookShieldRuleOperationResult
import com.xiyunmn.qdhelper.config.BookShieldRuleRepository
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.feature.bookshield.policy.WordCountBlockRule
import com.xiyunmn.qdhelper.feature.bookshield.stats.BookShieldStats
import com.xiyunmn.qdhelper.feature.bookshield.ui.BookShieldStatsText
import com.xiyunmn.qdhelper.feature.bookshield.ui.bookShieldMetadataParts
import com.xiyunmn.qdhelper.hook.concurrency.ManagedExecutor
import com.xiyunmn.qdhelper.settings.api.*

internal object BookShieldWordRangeTool : SettingsToolController {
    private val worker = ManagedExecutor("feature.book-shield.settings-range", "QDHelper-RangeSettings")
    override fun read(context: Context): SettingsToolState {
        val rules = BookShieldRuleRepository.snapshot(context)
        return SettingsToolState(
            text = "屏蔽 ≤ 下限或 ≥ 上限的书籍。留空停用对应一侧；字数未知或不大于 0 的书籍始终放行。",
            fields = listOf(SettingsToolField("atMost", "下限（≤）", rules.wordsAtMost?.toString().orEmpty(), true, rangeEnd = "atLeast"),
                SettingsToolField("atLeast", "上限（≥）", rules.wordsAtLeast?.toString().orEmpty(), true)),
            commands = listOf(SettingsToolCommand("clear-draft", "清空输入"), SettingsToolCommand("save", "保存")),
        )
    }
    override fun execute(activity: Activity, command: String, values: Map<String, String>, result: (SettingsToolResult) -> Unit): AutoCloseable? {
        val context = activity.applicationContext
        val low = values["atMost"].orEmpty().trim(); val high = values["atLeast"].orEmpty().trim()
        val rule = WordCountBlockRule.parse(low, high)
        if (rule.validationError != null) { result(SettingsToolResult(read(context).copy(error = rule.validationError))); return null }
        if (!worker.execute {
            val saved = BookShieldRuleRepository.update(context) { it.copy(wordsAtMost = rule.atMost, wordsAtLeast = rule.atLeast) }
            result(if (saved is BookShieldRuleOperationResult.Success) SettingsToolResult(message = "字数屏蔽范围已保存", close = true)
                else SettingsToolResult(read(context).copy(error = (saved as BookShieldRuleOperationResult.Failure).error.displayMessage())))
        }) result(SettingsToolResult(message = "保存未启动，请重新打开设置"))
        return null
    }
}

internal object BookShieldStatsTool : SettingsToolController {
    override val refreshIntervalMillis = 1500L
    private var mutation = Long.MIN_VALUE
    private var cached = SettingsToolState()
    override fun read(context: Context): SettingsToolState {
        val signature = BookShieldStats.mutationSignature()
        if (signature == mutation) return cached
        val snapshot = BookShieldStats.snapshot()
        mutation = signature
        cached = SettingsToolState(
            text = "本次运行共屏蔽 ${snapshot.totalRemoved} 本\n" + BookShieldStatsText.metaOf(snapshot) + "\n" + BookShieldStatsText.breakdownOf(snapshot),
            entries = snapshot.events.mapIndexed { index, event ->
                val reason = BookShieldStatsText.reasonText(event)
                val locationLabel = SettingsRuntime.bookShieldLocationLabel(event.location)
                val location = if (locationLabel == event.location) event.location else "$locationLabel（${event.location}）"
                SettingsToolEntry(
                    id = "${snapshot.sessionStart}:${snapshot.totalRemoved - index}",
                    title = event.title ?: "未知书名",
                    detail = listOf(
                        reason, location, bookShieldMetadataParts(event).joinToString(" · "),
                        java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(event.timestamp)),
                    ).joinToString("\n"),
                    emphasizedText = reason,
                )
            },
            commands = listOf(SettingsToolCommand("clear", "清空统计", "只清空本次运行统计，不修改屏蔽规则。")),
        )
        return cached
    }
    override fun execute(activity: Activity, command: String, values: Map<String, String>, result: (SettingsToolResult) -> Unit): AutoCloseable? {
        if (command == "clear") BookShieldStats.clear()
        result(SettingsToolResult(read(activity.applicationContext), "已清空统计")); return null
    }
}
