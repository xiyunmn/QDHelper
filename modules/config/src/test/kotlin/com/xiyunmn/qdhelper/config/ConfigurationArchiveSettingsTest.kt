package com.xiyunmn.qdhelper.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ConfigurationArchiveSettingsTest {
    @Test
    fun removedWelfareDisplayKeysAreTypedAndIgnored() {
        for (key in listOf("feature.welfare.ad_points.force_visible", "feature.welfare.guaranteed_lottery.force_visible")) {
            fun backup(value: String) = """{"format":"qdhelper-settings","version":1,"settings":{"module.enabled":true,"$key":$value}}"""
            assertEquals(mapOf("module.enabled" to true), ConfigurationArchive.parseSettings(backup("true")))
            assertEquals(mapOf("module.enabled" to true), ConfigurationArchive.parseSettings(backup("false")))
            assertThrows(IllegalArgumentException::class.java) { ConfigurationArchive.parseSettings(backup("1")) }
        }
    }

    @Test
    fun disclaimerAcceptanceCannotBeImported() {
        assertThrows(IllegalStateException::class.java) {
            ConfigurationArchive.parseSettings("""{"format":"qdhelper-settings","version":1,"settings":{"runtime.disclaimer.accepted":true}}""")
        }
    }

    @Test
    fun retiredExportSettingsDoNotPreventImportingExistingBackups() {
        val settings = """
            {"format":"qdhelper-settings","version":1,"settings":{
              "module.enabled":false,
              "feature.chapter_export.enabled":true,
              "feature.chapter_export.tree_uri":"content://documents/tree/old"
            }}
        """.trimIndent()
        assertEquals(mapOf("module.enabled" to false), ConfigurationArchive.parseSettings(settings))
        for (invalid in listOf(
            settings.replace("\"feature.chapter_export.enabled\":true", "\"feature.chapter_export.enabled\":\"true\""),
            settings.replace("\"content://documents/tree/old\"", "false"),
        )) {
            assertThrows(IllegalArgumentException::class.java) { ConfigurationArchive.parseSettings(invalid) }
        }
    }

    @Test
    fun independentGlassDimensionsRoundTripAndRejectInvalidImports() {
        val settings = """
            {"format":"qdhelper-settings","version":1,"settings":{
              "feature.glass_bars.main.height_dp":"96",
              "feature.glass_bars.detail.width_percent":"75",
              "feature.glass_bars.directory.bottom_gap_dp":"0",
              "feature.glass_bars.tilt":false
            }}
        """.trimIndent()
        val parsed = ConfigurationArchive.parseSettings(settings)
        assertEquals("96", parsed[SettingIds.KEY_GLASS_MAIN_HEIGHT])
        assertEquals("75", parsed[SettingIds.KEY_GLASS_DETAIL_WIDTH])
        assertEquals("0", parsed[SettingIds.KEY_GLASS_DIRECTORY_BOTTOM_GAP])
        assertEquals(false, parsed[SettingIds.KEY_GLASS_TILT])
        for (invalid in listOf("0", "121", "NaN", "64.5", "Infinity")) {
            assertThrows(IllegalArgumentException::class.java) {
                ConfigurationArchive.parseSettings(settings.replace("\"96\"", "\"$invalid\""))
            }
        }
    }

    @Test
    fun glassOptionsImportIndependentlyAndStrengthIsValidated() {
        val settings = """
            {"format":"qdhelper-settings","version":1,"settings":{
              "feature.glass_bars.main.enabled":true,
              "feature.glass_bars.detail.enabled":false,
              "feature.glass_bars.directory.enabled":true,
              "feature.glass_bars.dispersion":true,
              "feature.glass_bars.selection":false,
              "feature.glass_bars.strength":"strong"
            }}
        """.trimIndent()
        val parsed = ConfigurationArchive.parseSettings(settings)
        assertEquals(true, parsed[SettingIds.KEY_GLASS_MAIN_ENABLED])
        assertEquals(false, parsed[SettingIds.KEY_GLASS_DETAIL_ENABLED])
        assertEquals(true, parsed[SettingIds.KEY_GLASS_DIRECTORY_ENABLED])
        assertEquals(false, parsed[SettingIds.KEY_GLASS_SELECTION])
        assertEquals("strong", parsed[SettingIds.KEY_GLASS_STRENGTH])
        assertThrows(IllegalArgumentException::class.java) {
            ConfigurationArchive.parseSettings(settings.replace("strong", "invalid"))
        }
    }

    @Test
    fun retiredBooleanSettingsAreIgnoredWhileCurrentSettingsStillImport() {
        val settings = """
            {
              "format": "qdhelper-settings",
              "version": 1,
              "settings": {
                "module.enabled": true,
                "debug.dexkit.force_priority": true,
                "feature.startup.optimize_chain": false
              }
            }
        """.trimIndent()

        assertEquals(mapOf("module.enabled" to true), ConfigurationArchive.parseSettings(settings))
    }

    @Test
    fun unknownSettingsRemainRejected() {
        val settings = """
            {"format":"qdhelper-settings","version":1,"settings":{"unknown.setting":true}}
        """.trimIndent()

        assertThrows(IllegalStateException::class.java) {
            ConfigurationArchive.parseSettings(settings)
        }
    }

    @Test
    fun retiredPrioritySettingStillRequiresItsHistoricalBooleanType() {
        val settings = """
            {"format":"qdhelper-settings","version":1,"settings":{"debug.dexkit.force_priority":{}}}
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            ConfigurationArchive.parseSettings(settings)
        }
    }

    @Test
    fun schemaValidatorsRejectOutOfRangeImportedValues() {
        val settings = """
            {"format":"qdhelper-settings","version":1,"settings":{"feature.read_time.multiplier.value":"2.6"}}
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            ConfigurationArchive.parseSettings(settings)
        }
    }
}
