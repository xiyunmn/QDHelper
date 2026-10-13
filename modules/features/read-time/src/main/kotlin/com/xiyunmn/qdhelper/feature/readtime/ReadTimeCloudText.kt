package com.xiyunmn.qdhelper.feature.readtime

import com.xiyunmn.qdhelper.config.SettingsRuntime

internal object ReadTimeCloudText {
    internal fun settingsText(
        context: android.content.Context, userId: Long?, result: ReadTimeCloudProbe.Result?,
        local: ReadTimeLocalStatisticsStore.Totals?, verification: ReadTimeCloudVerification?,
    ): String = buildString {
        appendAccount(userId)
        if (result == null) append("\n\n未能获取云端数据，请检查网络和登录状态。")
        else {
            append("\n\n服务端记录：\n  今日：").append(formatSeconds(result.todaySeconds))
            append("\n  本周：").append(formatSeconds(result.weekSeconds))
        }
        appendLocalTotals(local)
        appendVerification(verification)
        val multiplier = ReadTimeCloudPresentationPolicy.multiplierState(
            enabled = SettingsRuntime.isReadTimeMultiplierEnabled(context),
            raw = SettingsRuntime.readTimeMultiplierRaw(context),
            automaticLimitRaw = ReadTimeCloudAuditStore.automaticLimit(context, userId)?.toString().orEmpty(),
        )
        append("\n\n当前倍率：").append(multiplier.label)
        append("\n本地显示：").append(if (SettingsRuntime.isReadTimeLocalDisplayRealEnabled(context)) "本地记录的原始时长" else "与上报一致")
        append("\n仅统计当前账号的阅读时长。")
    }

    private fun StringBuilder.appendVerification(value: ReadTimeCloudVerification?) {
        append("\n\n最近核验：")
        if (value == null) {
            append("暂无核验结果")
            return
        }
        if (value.cloudDeltaMillis == null) {
            append("已建立基线，产生并上报新的阅读记录后再次核验")
            return
        }
        append("\n  云端实际增量：").append(formatMillis(value.cloudDeltaMillis))
        append("\n  本地原始增量：").append(formatMillis(value.originalDeltaMillis))
        append("\n  本地上报增量：").append(formatMillis(value.reportedDeltaMillis))
        append("\n  实际认可倍率：").append(
            value.acceptedMultiplier?.let { String.format(java.util.Locale.US, "%.3fx", it) } ?: "--",
        )
        append("\n  折损时长：").append(formatMillis(value.discountedMillis))
        append("\n  判定：").append(verificationLabel(value))
        if (value.automaticLimitApplied) {
            append("（")
                .append("设定 ").append(formatMultiplier(value.configuredMultiplier, 1))
                .append(" · 实际 ").append(formatMultiplier(value.effectiveMultiplierAfter, 3))
                .append("）")
        }
    }

    private fun verificationLabel(value: ReadTimeCloudVerification): String = when (value.status) {
        ReadTimeCloudVerificationStatus.BASELINE -> "基线"
        ReadTimeCloudVerificationStatus.NO_REPORTS -> "无新增上报"
        ReadTimeCloudVerificationStatus.WAITING_FOR_CLOUD -> "等待云端更新"
        ReadTimeCloudVerificationStatus.ACCEPTED -> "未发现显著折损"
        ReadTimeCloudVerificationStatus.DISCOUNTED -> "已发现折损"
        ReadTimeCloudVerificationStatus.EFFECTIVE_LIMIT_APPLIED -> "已限制实际倍率，滑块保持不变"
        ReadTimeCloudVerificationStatus.RESET -> "基线已重置"
    }

    private fun formatMillis(value: Long?): String = value?.let { formatSeconds((it / 1_000L).toInt()) } ?: "--"

    private fun formatMultiplier(value: Double?, decimals: Int): String =
        value?.let { String.format(java.util.Locale.US, "%.${decimals}fx", it) } ?: "--"

    private fun StringBuilder.appendLocalTotals(local: ReadTimeLocalStatisticsStore.Totals?) {
        append("\n\n本地原始记录：")
        if (local == null) {
            append("\n  不可用")
            return
        }
        append("\n  今日：").append(formatSeconds(local.todaySeconds))
            .append("（").append(local.todayRecords).append(" 条）")
        append("\n  本周：").append(formatSeconds(local.weekSeconds))
            .append("（").append(local.weekRecords).append(" 条）")
    }

    private fun StringBuilder.appendAccount(userId: Long?) {
        append("当前账号：").append(when (userId) { null -> "无法识别"; 0L -> "游客"; else -> userId.toString() })
    }

    private fun formatSeconds(seconds: Int): String =
        if (seconds < 0) "--" else formatMinutes(seconds / 60)

    private fun formatMinutes(minutes: Int): String = when {
        minutes < 60 -> "${minutes} 分钟"
        else -> {
            val h = minutes / 60
            val m = minutes % 60
            if (m == 0) "${h} 小时" else "${h} 小时 ${m} 分钟"
        }
    }

}
