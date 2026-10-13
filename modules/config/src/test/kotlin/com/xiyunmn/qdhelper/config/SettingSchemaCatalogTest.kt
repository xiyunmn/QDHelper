package com.xiyunmn.qdhelper.config

import com.xiyunmn.qdhelper.config.schema.SettingSchemaCatalog
import com.xiyunmn.qdhelper.config.schema.SettingType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingSchemaCatalogTest {
    @Test
    fun schemaProducesUniqueTypedKeysWithMatchingScopes() {
        val definitions = SettingSchemaCatalog.definitions

        assertTrue(definitions.isNotEmpty())
        assertEquals(definitions.size, definitions.map { it.rawKey }.toSet().size)
        assertEquals(definitions.size, SettingSchemaCatalog.settingKeys.size)
        for (definition in definitions) {
            val key = SettingSchemaCatalog.requireKey(definition.rawKey)
            val expectedType = when (definition.type) {
                SettingType.BOOLEAN -> Boolean::class
                SettingType.STRING -> String::class
                SettingType.LONG -> Long::class
            }
            assertEquals(expectedType, key.valueType)
            assertEquals(definition.scope, key.scope)
            assertTrue(key.accepts(key.defaultValue))
        }
    }

    @Test
    fun validatorsRejectOutOfRangeValuesAndIncompleteOrders() {
        val typed = SettingSchemaCatalog.settingKeys.associateBy { it.rawKey }

        assertTrue(typed.getValue(SettingIds.KEY_READ_TIME_MULTIPLIER_VALUE).accepts("0.1"))
        assertTrue(typed.getValue(SettingIds.KEY_READ_TIME_MULTIPLIER_VALUE).accepts("2.5"))
        assertFalse(typed.getValue(SettingIds.KEY_READ_TIME_MULTIPLIER_VALUE).accepts("2.6"))
        assertFalse(typed.getValue(SettingIds.KEY_CUSTOM_DNS_NET_STACK).accepts("4"))
        assertFalse(typed.getValue(SettingIds.KEY_FREE_COPY_MODE).accepts("unknown"))
        assertFalse(typed.getValue(SettingIds.KEY_BOTTOM_TAB_ORDER).accepts("shelf,mine"))
    }
}
