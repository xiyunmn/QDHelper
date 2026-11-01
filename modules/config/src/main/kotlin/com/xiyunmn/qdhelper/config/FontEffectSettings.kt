package com.xiyunmn.qdhelper.config

import org.json.JSONObject

enum class FontEffectScope(val settingKey: String) {
    Reader(SettingIds.KEY_CUSTOM_READER_FONT_EFFECTS),
    Regular(SettingIds.KEY_CUSTOM_GLOBAL_FONT_REGULAR_EFFECTS),
    Emphasis(SettingIds.KEY_CUSTOM_GLOBAL_FONT_EMPHASIS_EFFECTS),
}

/** Zero weight preserves the font's own weight; spacing is an addition in hundredths of an em. */
data class FontEffectSettings(
    val weight: Int = 0,
    val sizePercent: Int = 100,
    val widthPercent: Int = 100,
    val spacing: Int = 0,
) {
    val sizeScale get() = sizePercent / 100f
    val widthScale get() = widthPercent / 100f
    val letterSpacing get() = spacing / 100f
    val changesMetrics get() = sizePercent != 100 || widthPercent != 100 || spacing != 0

    fun normalized() = copy(
        weight = if (weight == 0) 0 else weight.coerceIn(100, 900),
        sizePercent = sizePercent.coerceIn(75, 150),
        widthPercent = widthPercent.coerceIn(75, 125),
        spacing = spacing.coerceIn(-5, 30),
    )

    fun encode(): String {
        val value = normalized()
        if (value == FontEffectSettings()) return ""
        return JSONObject().put("version", 1).put("weight", value.weight)
            .put("size", value.sizePercent).put("width", value.widthPercent)
            .put("spacing", value.spacing).toString()
    }

    companion object {
        fun accepts(raw: String): Boolean = raw.isBlank() || runCatching {
            val value = JSONObject(raw)
            value.getInt("version") == 1 && listOf("weight", "size", "width", "spacing").all {
                value.get(it) is Int
            } && FontEffectSettings(value.getInt("weight"), value.getInt("size"),
                value.getInt("width"), value.getInt("spacing")).let { it == it.normalized() }
        }.getOrDefault(false)

        fun decode(raw: String): FontEffectSettings = runCatching {
            if (raw.isBlank()) return FontEffectSettings()
            val value = JSONObject(raw)
            if (value.optInt("version") != 1) return FontEffectSettings()
            FontEffectSettings(value.optInt("weight", 0), value.optInt("size", 100),
                value.optInt("width", 100), value.optInt("spacing", 0)).normalized()
        }.getOrDefault(FontEffectSettings())
    }
}
