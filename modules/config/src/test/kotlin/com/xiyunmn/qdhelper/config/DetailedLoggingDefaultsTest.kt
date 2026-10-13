package com.xiyunmn.qdhelper.config

import com.xiyunmn.qdhelper.config.schema.SettingSchemaCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailedLoggingDefaultsTest {
    @Test
    fun selectedChildrenStayInactiveUntilTheMasterIsEnabled() {
        ConfigRuntime.clear()
        assertEquals(false, SettingSchemaCatalog.requireKey(SettingIds.KEY_DETAILED_LOG_ENABLED).defaultValue)
        listOf(SettingIds.KEY_DEBUG_DISCOVERY_ENABLED, SettingIds.KEY_DEBUG_CALL_PROBE_ENABLED,
            SettingIds.KEY_DEBUG_DEVICE_IDENTITY_PROBE_ENABLED, SettingIds.KEY_DEBUG_HOST_COLLECT_JSON_ENABLED,
            SettingIds.KEY_DEBUG_SHIELD_TRACE_ENABLED, SettingIds.KEY_DEBUG_WELFARE_REWARD_PROBE_ENABLED,
            SettingIds.KEY_DEBUG_DEXKIT_LOG_CANDIDATES).forEach {
            assertEquals(true, SettingSchemaCatalog.requireKey(it).defaultValue)
        }
        assertFalse(SettingsRuntime.isDiscoveryProbeEnabled())
        assertFalse(SettingsRuntime.isCallProbeEnabled())
        assertFalse(SettingsRuntime.isDeviceIdentityProbeEnabled())
        assertFalse(SettingsRuntime.isHostCollectJsonEnabled())
        assertFalse(SettingsRuntime.isShieldTraceEnabled())
        assertFalse(SettingsRuntime.isWelfareRewardProbeEnabled())
        assertFalse(SettingsRuntime.isDexKitCandidateLogEnabled())
        assertFalse(SettingsRuntime.isFileLogEnabled())
        assertTrue(SettingsRuntime.isDexKitEnabled())
    }
}
