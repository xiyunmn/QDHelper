package com.xiyunmn.qdhelper.config

internal object GlassEffectSettingsMigration {
    private val replacements = listOf(
        Triple(SettingIds.KEY_GLASS_BLUR, SettingIds.KEY_GLASS_BLUR_RADIUS, "7"),
        Triple(SettingIds.KEY_GLASS_REFRACTION, SettingIds.KEY_GLASS_REFRACTION_PERCENT, "100"),
        Triple(SettingIds.KEY_GLASS_HIGHLIGHT, SettingIds.KEY_GLASS_HIGHLIGHT_PERCENT, "100"),
        Triple(SettingIds.KEY_GLASS_SHADOW, SettingIds.KEY_GLASS_SHADOW_PERCENT, "100"),
    )

    // Resolve against this import's values, so an old backup can replace newer local intensities.
    fun missingValues(values: Map<String, *>): Map<String, String> = buildMap {
        replacements.forEach { (oldKey, newKey, enabledValue) ->
            val enabled = values[oldKey] as? Boolean
            if (newKey !in values && enabled != null) put(newKey, if (enabled) enabledValue else "0")
        }
    }
}
