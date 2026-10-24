package com.xiyunmn.qdhelper.settings.ui.compose.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.xiyunmn.qdhelper.settings.ui.compose.presentation.SettingsPresentationCatalog

internal val LocalSettingsIconColors = staticCompositionLocalOf<Map<String, SettingsIconColor>> { emptyMap() }

internal enum class SettingsIconColor(val light: Color, val dark: Color) {
    Blue(Color(0xFF2563EB), Color(0xFF60A5FA)),
    Violet(Color(0xFF8B5CF6), Color(0xFFA78BFA)),
    Teal(Color(0xFF0D9488), Color(0xFF2DD4BF)),
    Amber(Color(0xFFD97706), Color(0xFFFBBF24)),
    Green(Color(0xFF16A34A), Color(0xFF4ADE80)),
    Slate(Color(0xFF64748B), Color(0xFF94A3B8)),
}

private val defaultIconColors = mapOf(
    "reading" to SettingsIconColor.Blue,
    "interface" to SettingsIconColor.Violet,
    "content" to SettingsIconColor.Teal,
    "account" to SettingsIconColor.Amber,
    "privacy" to SettingsIconColor.Green,
    "tools" to SettingsIconColor.Slate,
    // Dedicated destinations keep their own functional color within a broader category.
    "shield" to SettingsIconColor.Green,
    "shield-quick" to SettingsIconColor.Blue,
    "shield-yaml" to SettingsIconColor.Violet,
    "book-shield.stats" to SettingsIconColor.Blue,
    "page-customization" to SettingsIconColor.Violet,
    "font-library" to SettingsIconColor.Violet,
    "backup" to SettingsIconColor.Blue,
    "cookie" to SettingsIconColor.Amber,
    "resolver" to SettingsIconColor.Teal,
    "probes" to SettingsIconColor.Violet,
)

/** A destination overrides its parent; its ordinary children inherit that closest choice. */
internal fun settingsIconColors(catalog: SettingsPresentationCatalog,
    overrides: Map<String, SettingsIconColor> = emptyMap()): Map<String, SettingsIconColor> {
    val explicit = defaultIconColors + overrides
    val pages = catalog.destinations.keys.mapNotNull { id ->
        generateSequence(id) { catalog.destinations[it]?.parent }
            .firstNotNullOfOrNull { explicit[it] }?.let { id to it }
    }.toMap()
    return buildMap {
        putAll(pages)
        catalog.metadata.forEach { (id, meta) ->
            (explicit[id] ?: pages[meta.destination])?.let { put(id, it) }
        }
    }
}

@Composable
internal fun categoryAccent(id: String): Color {
    val palette = LocalSettingsIconColors.current[id] ?: defaultIconColors[id] ?: return MaterialTheme.colorScheme.primary
    return if (MaterialTheme.colorScheme.surface.luminance() < .4f) palette.dark else palette.light
}
