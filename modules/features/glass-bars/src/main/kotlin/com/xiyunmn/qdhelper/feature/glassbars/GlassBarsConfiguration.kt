package com.xiyunmn.qdhelper.feature.glassbars

import android.content.Context
import com.xiyunmn.qdhelper.config.ConfigRuntime
import com.xiyunmn.qdhelper.config.GlassBarDimensions
import com.xiyunmn.qdhelper.config.SettingIds
import com.xiyunmn.qdhelper.shared.ui.glass.GlassEffectsSpec

internal enum class GlassBarKind(val settingKey: String, val heightKey: String, val widthKey: String, val gapKey: String) {
    MAIN(SettingIds.KEY_GLASS_MAIN_ENABLED, SettingIds.KEY_GLASS_MAIN_HEIGHT, SettingIds.KEY_GLASS_MAIN_WIDTH, SettingIds.KEY_GLASS_MAIN_BOTTOM_GAP),
    DETAIL(SettingIds.KEY_GLASS_DETAIL_ENABLED, SettingIds.KEY_GLASS_DETAIL_HEIGHT, SettingIds.KEY_GLASS_DETAIL_WIDTH, SettingIds.KEY_GLASS_DETAIL_BOTTOM_GAP),
    DIRECTORY(SettingIds.KEY_GLASS_DIRECTORY_ENABLED, SettingIds.KEY_GLASS_DIRECTORY_HEIGHT, SettingIds.KEY_GLASS_DIRECTORY_WIDTH, SettingIds.KEY_GLASS_DIRECTORY_BOTTOM_GAP),
    ;

    val defaultWidthPercent: Int get() = if (this == MAIN) 80 else 0
}

internal data class GlassBarsConfiguration(
    val enabled: Set<GlassBarKind> = emptySet(),
    val effects: GlassEffectsSpec = GlassEffectsSpec(),
    val dimensions: Map<GlassBarKind, GlassBarDimensions> = emptyMap(),
) {
    fun dimensionsFor(kind: GlassBarKind): GlassBarDimensions =
        dimensions[kind] ?: GlassBarDimensions(widthPercent = kind.defaultWidthPercent)

    companion object {
        val observedKeys = setOf(
            SettingIds.KEY_MODULE_ENABLED,
            SettingIds.KEY_GLASS_BARS_ENABLED,
            SettingIds.KEY_GLASS_MAIN_ENABLED, SettingIds.KEY_GLASS_DETAIL_ENABLED, SettingIds.KEY_GLASS_DIRECTORY_ENABLED,
            SettingIds.KEY_GLASS_BLUR, SettingIds.KEY_GLASS_REFRACTION,
            SettingIds.KEY_GLASS_HIGHLIGHT, SettingIds.KEY_GLASS_SHADOW, SettingIds.KEY_GLASS_ANIMATION,
            SettingIds.KEY_GLASS_TILT,
        ) + GlassBarKind.entries.flatMap { listOf(it.heightKey, it.widthKey, it.gapKey) }

        fun read(context: Context?): GlassBarsConfiguration {
            context?.let(ConfigRuntime::initialize)
            return fromValues(ConfigRuntime::readBoolean, ConfigRuntime::readString)
        }

        internal fun fromValues(boolean: (String) -> Boolean, string: (String) -> String): GlassBarsConfiguration =
            GlassBarsConfiguration(
                enabled = if (
                    boolean(SettingIds.KEY_MODULE_ENABLED) && boolean(SettingIds.KEY_GLASS_BARS_ENABLED)
                ) {
                    GlassBarKind.entries.filterTo(linkedSetOf()) { boolean(it.settingKey) }
                } else emptySet(),
                effects = GlassEffectsSpec(
                    blur = boolean(SettingIds.KEY_GLASS_BLUR),
                    refraction = boolean(SettingIds.KEY_GLASS_REFRACTION),
                    highlight = boolean(SettingIds.KEY_GLASS_HIGHLIGHT),
                    shadow = boolean(SettingIds.KEY_GLASS_SHADOW),
                    tilt = boolean(SettingIds.KEY_GLASS_TILT),
                    press = boolean(SettingIds.KEY_GLASS_ANIMATION),
                ),
                dimensions = GlassBarKind.entries.associateWith {
                    GlassBarDimensions.fromStored(string(it.heightKey), string(it.widthKey), string(it.gapKey))
                },
            )
    }
}
