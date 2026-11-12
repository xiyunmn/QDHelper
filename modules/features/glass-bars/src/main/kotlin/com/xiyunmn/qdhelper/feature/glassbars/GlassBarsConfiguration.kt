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

    val defaultWidthPercent: Int get() = GlassBarDimensions.DEFAULT_WIDTH
}

internal data class GlassBarsConfiguration(
    val enabled: Set<GlassBarKind> = emptySet(),
    val effects: GlassEffectsSpec = GlassEffectsSpec(),
    val dimensions: Map<GlassBarKind, GlassBarDimensions> = emptyMap(),
    val haptics: Boolean = true,
) {
    fun dimensionsFor(kind: GlassBarKind): GlassBarDimensions =
        dimensions[kind] ?: GlassBarDimensions(widthPercent = kind.defaultWidthPercent)

    companion object {
        val observedKeys = setOf(
            SettingIds.KEY_MODULE_ENABLED,
            SettingIds.KEY_GLASS_BARS_ENABLED,
            SettingIds.KEY_GLASS_MAIN_ENABLED, SettingIds.KEY_GLASS_DETAIL_ENABLED, SettingIds.KEY_GLASS_DIRECTORY_ENABLED,
            SettingIds.KEY_GLASS_BLUR_RADIUS, SettingIds.KEY_GLASS_REFRACTION_PERCENT,
            SettingIds.KEY_GLASS_SURFACE_OPACITY, SettingIds.KEY_GLASS_HIGHLIGHT_PERCENT,
            SettingIds.KEY_GLASS_SHADOW_PERCENT, SettingIds.KEY_GLASS_DRAG_STRENGTH, SettingIds.KEY_GLASS_ANIMATION,
            SettingIds.KEY_GLASS_CAPSULE_SIZE,
            SettingIds.KEY_GLASS_CLICK_SPEED,
            SettingIds.KEY_GLASS_TILT,
            SettingIds.KEY_GLASS_HAPTICS,
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
                    blurRadiusDp = number(string, SettingIds.KEY_GLASS_BLUR_RADIUS, 7, 14),
                    refractionScale = number(string, SettingIds.KEY_GLASS_REFRACTION_PERCENT, 100, 200) / 100f,
                    surfaceOpacity = number(string, SettingIds.KEY_GLASS_SURFACE_OPACITY, 50, 100) / 100f,
                    highlightOpacity = number(string, SettingIds.KEY_GLASS_HIGHLIGHT_PERCENT, 100, 200) / 100f * 0.75f,
                    shadowScale = number(string, SettingIds.KEY_GLASS_SHADOW_PERCENT, 100, 200) / 100f,
                    dragStrength = number(string, SettingIds.KEY_GLASS_DRAG_STRENGTH, 100, 200) / 100f,
                    capsuleScale = number(string, SettingIds.KEY_GLASS_CAPSULE_SIZE, 100, 125).coerceAtLeast(75f) / 100f * 1.05f,
                    clickSpeed = number(string, SettingIds.KEY_GLASS_CLICK_SPEED, 100, 175).coerceAtLeast(25f) / 100f * 0.6f,
                    tilt = boolean(SettingIds.KEY_GLASS_TILT),
                    press = boolean(SettingIds.KEY_GLASS_ANIMATION),
                ),
                dimensions = GlassBarKind.entries.associateWith {
                    GlassBarDimensions.fromStored(string(it.heightKey), string(it.widthKey), string(it.gapKey))
                },
                haptics = boolean(SettingIds.KEY_GLASS_HAPTICS),
            )

        private fun number(string: (String) -> String, key: String, default: Int, maximum: Int): Float =
            (string(key).toIntOrNull() ?: default).coerceIn(0, maximum).toFloat()
    }
}
