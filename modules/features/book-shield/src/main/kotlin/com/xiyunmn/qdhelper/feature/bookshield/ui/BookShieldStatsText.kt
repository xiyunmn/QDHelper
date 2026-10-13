package com.xiyunmn.qdhelper.feature.bookshield.ui

import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.feature.bookshield.stats.BookShieldStats
import com.xiyunmn.qdhelper.feature.bookshield.stats.ShieldEvent
import com.xiyunmn.qdhelper.feature.bookshield.policy.ShieldReasonKind
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun bookShieldMetadataParts(event: ShieldEvent): List<String> = buildList {
    event.author?.trim()?.takeIf(String::isNotEmpty)?.let { add("作者 $it") }
    formatShieldWords(event.words)?.let { add("字数 $it") }
    event.category?.trim()?.takeIf(String::isNotEmpty)?.let { add("分类 $it") }
    event.tags?.trim()?.takeIf(String::isNotEmpty)?.let { add("标签 $it") }
    event.bookId?.takeIf { it > 0L }?.let { add("书号 $it") }
}

internal fun formatShieldWords(words: Long?): String? {
    val value = words ?: return null
    if (value <= 0L) return null
    return if (value >= 10000L) {
        String.format(Locale.getDefault(), "%.1f万字", value / 10000.0)
    } else {
        "${value}字"
    }
}

/** Text shared by the statistics controller and its copy/export output. */
internal object BookShieldStatsText {
    private val sessionTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    internal fun metaOf(snapshot: BookShieldStats.Snapshot): String {
        return buildString {
            append("明细 ")
            append(snapshot.retained)
            append(" 条")
            if (snapshot.dropped > 0L) {
                append(" · 已滚动 ")
                append(snapshot.dropped)
                append(" 条")
            }
            if (snapshot.sessionStart > 0L) {
                append(" · 起 ")
                append(sessionTimeFormat.format(Date(snapshot.sessionStart)))
            }
        }
    }

    internal fun breakdownOf(snapshot: BookShieldStats.Snapshot): String {
        val byLocation = snapshot.locationCounts.entries
            .sortedByDescending { it.value }
            .joinToString("、") { "${SettingsRuntime.bookShieldLocationLabel(it.key)} ×${it.value}" }
            .ifEmpty { "—" }
        val byReason = snapshot.reasonCounts.entries
            .sortedByDescending { it.value }
            .joinToString("、") { "${it.key.label} ×${it.value}" }
            .ifEmpty { "—" }
        return "按位置：$byLocation\n按原因：$byReason"
    }

    internal fun reasonText(event: ShieldEvent): String {
        return when (event.reasonKind) {
            ShieldReasonKind.BOOK_ID -> "命中书号 ${event.reasonDetail}"
            ShieldReasonKind.AUTHOR -> "作者「${event.reasonDetail}」"
            ShieldReasonKind.FULL_TITLE -> "完整书名命中"
            ShieldReasonKind.KEYWORD -> "书名含「${event.reasonDetail}」"
            ShieldReasonKind.TYPE -> "分类「${event.reasonDetail}」"
            ShieldReasonKind.WORDS_TOO_SHORT -> "字数过少 ${event.reasonDetail}"
            ShieldReasonKind.WORDS_TOO_LONG -> "字数过多 ${event.reasonDetail}"
        }
    }

}
