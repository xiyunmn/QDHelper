package com.xiyunmn.qdhelper.feature.readtime

import java.util.Locale

internal object ReadTimeCloudPresentationPolicy {
    data class MultiplierState(
        val enabled: Boolean,
        val configuredMultiplier: Double?,
        val effectiveMultiplier: Double?,
        val label: String,
    ) {
        val activeMultiplier: Double?
            get() = effectiveMultiplier?.takeIf { enabled }
    }

    fun multiplierState(enabled: Boolean, raw: String, automaticLimitRaw: String = ""): MultiplierState {
        val configured = ReadTimeMultiplierPolicy.parseMultiplier(raw)
            ?: ReadTimeMultiplierPolicy.migrateStoredMultiplier(raw)
        val automaticLimit = automaticLimitRaw.toDoubleOrNull()
        val effective = configured?.let { ReadTimeMultiplierPolicy.effectiveMultiplier(it, automaticLimit) }
        val label = when {
            !enabled && configured != null -> "已关闭（设定 ${formatConfigured(configured)}）"
            !enabled -> "已关闭"
            configured != null && effective != null ->
                "设定 ${formatConfigured(configured)} · 实际 ${formatEffective(effective)}"
            else -> "已开启，但倍率无效"
        }
        return MultiplierState(enabled, configured, effective, label)
    }

    private fun formatConfigured(value: Double): String = String.format(Locale.US, "%.1fx", value)
    private fun formatEffective(value: Double): String = String.format(Locale.US, "%.3fx", value)
}
