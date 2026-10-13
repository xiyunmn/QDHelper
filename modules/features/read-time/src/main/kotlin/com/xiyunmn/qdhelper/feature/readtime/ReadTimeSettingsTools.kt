package com.xiyunmn.qdhelper.feature.readtime

import android.app.Activity
import android.content.Context
import com.xiyunmn.qdhelper.settings.api.*

internal object ReadTimeAuditTool : SettingsToolController {
    override val refreshIntervalMillis = 1500L
    override fun read(context: Context): SettingsToolState {
        val userId = ReadTimeRuntime.userId(context)
        val state = ReadTimeReportAuditStore.snapshot(context, userId)
        val cloud = ReadTimeCloudAuditStore.snapshot(context, userId).latest
        return SettingsToolState(
            text = "当前账号：${userId ?: "无法识别"}\n仅显示当前账号的记录。已滚动 ${state.droppedEvents} 条。\n\n" + ReadTimeAuditText.countersText("今日", state.today) + "\n\n" + ReadTimeAuditText.countersText("本次启动", state.session) + "\n\n" + ReadTimeAuditText.cloudVerificationText(cloud),
            entries = state.events.asReversed().mapIndexed { index, event -> SettingsToolEntry("$userId:${event.recordedAt}:${state.droppedEvents + state.events.size - index}", ReadTimeAuditText.eventTitle(event), listOf(ReadTimeAuditText.durationLine(event), ReadTimeAuditText.resultLine(event), ReadTimeAuditText.metadataLines(event).joinToString("\n")).joinToString("\n")) },
            commands = listOf(SettingsToolCommand("clear", "清空本账号审计记录", "清除审计和核验基线；原始阅读明细与自动倍率限制保留。")),
        )
    }
    override fun execute(activity: Activity, command: String, values: Map<String, String>, result: (SettingsToolResult) -> Unit): AutoCloseable? {
        val app = activity.applicationContext
        val user = ReadTimeRuntime.userId(app)
        val cleared = user != null && ReadTimeRuntime.session(app)?.clearAudit(user) == true
        result(SettingsToolResult(read(app), if (cleared) "已清空审计；原始记录与倍率限制保留" else "清空未完成，请确认当前账号后重试"))
        return null
    }
}

internal object ReadTimeCloudTool : SettingsToolController {
    override val initialCommand = "query"
    override fun read(context: Context) = SettingsToolState("正在拉取服务端记录…")
    override fun execute(activity: Activity, command: String, values: Map<String, String>, result: (SettingsToolResult) -> Unit): AutoCloseable? {
        val app = activity.applicationContext
        val loader = activity.javaClass.classLoader
        val query = loader?.let { ReadTimeRuntime.session(app, it) }?.query { response ->
            val text = if (response.cancelled) "查询已取消，请重试。" else ReadTimeCloudText.settingsText(app, response.userId, response.cloud, response.local, response.verification)
            result(SettingsToolResult(SettingsToolState(text, commands = listOf(SettingsToolCommand("query", "重新核验")))))
        }
        if (query == null) result(SettingsToolResult(SettingsToolState("当前环境暂不支持云端查询", commands = listOf(SettingsToolCommand("query", "重试")))))
        return query
    }
}
