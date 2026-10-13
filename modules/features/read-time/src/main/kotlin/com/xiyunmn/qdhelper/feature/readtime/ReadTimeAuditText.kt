package com.xiyunmn.qdhelper.feature.readtime

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object ReadTimeAuditText {
    private val dateTimeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    internal fun countersText(label: String, value: ReadTimeAuditCounters): String = buildString {
        append(label).append("已上报：").append(value.reportedRecords).append(" 条")
        append("\n调整已上报：").append(value.adjustedReportedRecords)
            .append(" / ").append(value.adjustmentInsertSuccesses).append(" 条")
        append(" · 调整入库：").append(value.adjustmentInsertSuccesses)
            .append(" / ").append(value.adjustmentAttempts)
        append("\nDAO 入库：").append(value.insertSuccesses).append(" / ").append(value.insertAttempts)
        append(" · 封顶：").append(value.clampedInsertSuccesses)
        append("\n上报原始时长：").append(formatDuration(value.reportedOriginalMillis))
        append("\n实际上报时长：").append(formatDuration(value.reportedMillis))
        append(" · 额外：").append(formatSignedDuration(value.extraReportedMillis))
    }

    internal fun cloudVerificationText(value: ReadTimeCloudVerification?): String = buildString {
        append("最近云端核验：")
        if (value == null) {
            append("尚未建立基线")
            return@buildString
        }
        if (value.cloudDeltaMillis == null) {
            append("基线已建立")
            return@buildString
        }
        append("云端 +").append(formatDuration(value.cloudDeltaMillis))
        append(" · 原始 ").append(formatDuration(value.originalDeltaMillis ?: 0L))
        append(" · 上报 ").append(formatDuration(value.reportedDeltaMillis ?: 0L))
        append("\n实际认可倍率：")
            .append(value.acceptedMultiplier?.let { String.format(Locale.US, "%.3fx", it) } ?: "--")
        append(" · 折损：").append(formatDuration(value.discountedMillis ?: 0L))
        if (value.automaticLimitApplied) {
            append("\n实际倍率已限制：设定 ")
                .append(value.configuredMultiplier?.let { String.format(Locale.US, "%.1fx", it) } ?: "--")
                .append(" · 生效 ")
                .append(value.effectiveMultiplierAfter?.let { String.format(Locale.US, "%.3fx", it) } ?: "--")
                .append(" · 滑块未修改")
        }
    }

    internal fun eventTitle(event: ReadTimeAuditEvent): String {
        val status = when {
            !event.daoSuccess -> "入库失败"
            event.reportedAt != null -> "已上报"
            else -> "待宿主上报"
        }
        val multiplier = event.multiplier?.let { " · x$it" }.orEmpty()
        val book = event.details.bookName ?: event.details.bookId.takeIf { it > 0 }?.let { "书号 $it" } ?: "未知来源"
        return "${timeFormat.format(Date(event.recordedAt))} · $status$multiplier · $book"
    }

    internal fun durationLine(event: ReadTimeAuditEvent): String = buildString {
        append("原始 ").append(formatPrecise(event.originalMillis))
        append(" → 入库 ").append(formatPrecise(event.persistedMillis))
        append(" · 差值 ").append(formatSignedPrecise(event.persistedMillis - event.originalMillis))
    }

    internal fun resultLine(event: ReadTimeAuditEvent): String = buildString {
        append(if (event.adjustmentApplied) "调整已应用" else if (event.adjustmentRequested) "调整未应用" else "未调整")
        append(" · ").append(if (event.clamped) "触及 10 分钟封顶" else "未封顶")
        append(" · DAO ").append(event.daoLatencyMillis).append("ms")
        append(" · 密文").append(if (event.encodedAfterDao) "已生成" else "缺失")
        event.scaleError?.let { append(" · 调整错误 ").append(it) }
        event.daoError?.let { append(" · DAO 异常 ").append(it) }
        if (event.restoreFailed) append(" · 实体恢复失败")
    }

    internal fun metadataLines(event: ReadTimeAuditEvent): List<String> = buildList {
        val d = event.details
        add("记录：${dateTime(d.startTime)} → ${dateTime(d.endTime)} · 墙钟 ${formatPrecise((d.endTime - d.startTime).coerceAtLeast(0))}")
        add("书号 ${d.bookId} · 类型 ${d.bookType} · 章节 ${d.startChapterId} → ${d.endChapterId}")
        add("VIP ${d.chapterVip} · 翻页 ${d.scrollMode} · 解锁 ${d.unlockStatus}/${d.unlockReason}")
        d.source?.let { add("来源：$it") }
        d.extraJson?.let { add("扩展：$it") }
        event.reportedAt?.let { add("宿主网络成功回调：${dateTimeFormat.format(Date(it))}") }
    }

    private fun formatDuration(millis: Long): String {
        val seconds = millis / 1000
        val hours = seconds / 3600
        val minutes = seconds % 3600 / 60
        val remaining = seconds % 60
        return buildString {
            if (hours > 0) append(hours).append(" 小时 ")
            if (hours > 0 || minutes > 0) append(minutes).append(" 分 ")
            append(remaining).append(" 秒")
        }
    }

    private fun formatSignedDuration(millis: Long): String =
        (if (millis >= 0) "+" else "-") + formatDuration(kotlin.math.abs(millis))

    private fun formatPrecise(millis: Long): String = String.format(Locale.getDefault(), "%.3f 秒", millis / 1000.0)

    private fun formatSignedPrecise(millis: Long): String =
        String.format(Locale.getDefault(), "%+.3f 秒", millis / 1000.0)

    private fun dateTime(timestamp: Long): String =
        if (timestamp > 0) dateTimeFormat.format(Date(timestamp)) else "--"

}
