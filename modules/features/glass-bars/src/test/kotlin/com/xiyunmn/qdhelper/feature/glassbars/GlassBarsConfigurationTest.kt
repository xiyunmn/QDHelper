package com.xiyunmn.qdhelper.feature.glassbars

import com.xiyunmn.qdhelper.config.SettingIds
import com.xiyunmn.qdhelper.config.GlassBarDimensions
import com.xiyunmn.qdhelper.config.schema.SettingSchemaCatalog
import com.xiyunmn.qdhelper.settings.api.DefaultSettingRegistry
import com.xiyunmn.qdhelper.settings.api.SettingsPageIds
import com.xiyunmn.qdhelper.settings.api.SettingsRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassBarsConfigurationTest {
    @Test
    fun eachBarCanBeEnabledAloneAndBothMasterSwitchesGateAllThree() {
        for (kind in GlassBarKind.entries) {
            val values = defaults().apply {
                put(SettingIds.KEY_MODULE_ENABLED, true)
                put(SettingIds.KEY_GLASS_BARS_ENABLED, true)
                put(kind.settingKey, true)
            }
            assertEquals(setOf(kind), configuration(values).enabled)
            values[SettingIds.KEY_MODULE_ENABLED] = false
            assertTrue(configuration(values).enabled.isEmpty())
            values[SettingIds.KEY_MODULE_ENABLED] = true
            values[SettingIds.KEY_GLASS_BARS_ENABLED] = false
            assertTrue(configuration(values).enabled.isEmpty())
        }
    }

    @Test
    fun resettingUsesOptInDefaultsAndRestoresTheSharedMaterial() {
        val values = defaults()
        val configuration = configuration(values)
        assertTrue(configuration.enabled.isEmpty())
        assertTrue(configuration.effects.blur)
        assertTrue(configuration.effects.refraction)
        assertTrue(configuration.effects.highlight)
        assertTrue(configuration.effects.shadow)
        assertTrue(configuration.effects.press)
        assertTrue(configuration.effects.tilt)
        assertEquals(GlassBarDimensions(widthPercent = 80), configuration.dimensionsFor(GlassBarKind.MAIN))
        assertEquals(GlassBarDimensions(), configuration.dimensionsFor(GlassBarKind.DETAIL))
        assertEquals(GlassBarDimensions(), configuration.dimensionsFor(GlassBarKind.DIRECTORY))
    }

    @Test
    fun disablingPressKeepsOpticsAndOldSelectionPreferencesDoNotDisableTheNewMaterial() {
        val values = defaults().apply {
            put(SettingIds.KEY_MODULE_ENABLED, true)
            put(SettingIds.KEY_GLASS_BARS_ENABLED, true)
            put(SettingIds.KEY_GLASS_MAIN_ENABLED, true)
            put(SettingIds.KEY_GLASS_SELECTION, false)
            put(SettingIds.KEY_GLASS_ANIMATION, false)
        }
        val configuration = configuration(values)
        assertEquals(setOf(GlassBarKind.MAIN), configuration.enabled)
        assertFalse(configuration.effects.press)
        assertTrue(configuration.effects.highlight)
        assertTrue(configuration.effects.refraction)
    }

    @Test
    fun pageOwnsAllKeysAndVisibleDefaultsMatchSchema() {
        DefaultSettingRegistry(SettingSchemaCatalog.settingKeys, listOf(GlassBarsSettingsContribution))
        val sections = GlassBarsSettingsContribution.sections.filter { it.page == SettingsPageIds.GLASS_BARS }
        val rows = sections.flatMap { it.rows }
        assertEquals(
            GlassBarsConfiguration.observedKeys - SettingIds.KEY_MODULE_ENABLED - SettingIds.KEY_GLASS_BARS_ENABLED,
            rows.flatMap { it.settingKeys }.toSet(),
        )
        rows.filterIsInstance<SettingsRow.Toggle>().forEach { row ->
            assertEquals(SettingSchemaCatalog.requireKey(row.key).defaultValue, row.defaultValue)
        }
        val sliders = rows.filterIsInstance<SettingsRow.Slider>()
        assertEquals(9, sliders.size)
        sliders.forEach { row ->
            val key = SettingSchemaCatalog.requireKey(row.key)
            assertEquals(key.defaultValue, row.defaultValue.toInt().toString())
            assertTrue(key.accepts(row.minValue.toInt().toString()))
            assertTrue(key.accepts(row.maxValue.toInt().toString()))
            assertFalse(key.accepts((row.maxValue.toInt() + 1).toString()))
        }
        assertEquals(6, sections.single { it.id == "glass-bars.effects" }.rows.filterIsInstance<SettingsRow.Toggle>().size)

        val entry = GlassBarsSettingsContribution.sections.single { it.id == "glass-bars.entry" }
        assertEquals(SettingsPageIds.NAVIGATION, entry.page)
        assertEquals(0, entry.order)
        assertEquals(
            SettingIds.KEY_GLASS_BARS_ENABLED,
            (entry.rows.first() as SettingsRow.Toggle).key,
        )
    }

    @Test
    fun allNineDimensionsRemainIndependentAcrossLocations() {
        for (kind in GlassBarKind.entries) {
            val values = defaults().apply {
                put(kind.heightKey, "96")
                put(kind.widthKey, "75")
                put(kind.gapKey, "32")
            }
            val configuration = configuration(values)
            for (other in GlassBarKind.entries) {
                val expected = if (other == kind) GlassBarDimensions(96, 75, 32)
                    else GlassBarDimensions(widthPercent = if (other == GlassBarKind.MAIN) 80 else 0)
                assertEquals(expected, configuration.dimensionsFor(other))
            }
        }
    }

    @Test
    fun corruptLocalValuesFallBackOrClampWhileImportsAreValidated() {
        assertEquals(GlassBarDimensions(), GlassBarDimensions.fromStored("NaN", "Infinity", "oops"))
        assertEquals(GlassBarDimensions(120, 100, 120), GlassBarDimensions.fromStored("1000", "200", "200"))
        assertEquals(GlassBarDimensions(48, 0, -1), GlassBarDimensions.fromStored("-8", "-100", "-20"))
    }

    private fun defaults() = SettingSchemaCatalog.settingKeys.associate { it.rawKey to it.defaultValue }.toMutableMap()
    private fun configuration(values: Map<String, Any>) = GlassBarsConfiguration.fromValues(
        { values.getValue(it) as Boolean }, { values.getValue(it) as String },
    )
}
