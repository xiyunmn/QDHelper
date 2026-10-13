package com.xiyunmn.qdhelper.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SettingContractsTest {
    @Test
    fun identityUsesCompatibleRawKeyAndValidatesDefault() {
        val first = SettingKey(
            rawKey = "reader.copy.enabled",
            valueType = Boolean::class,
            defaultValue = false,
            scope = SettingScope.USER,
        )
        val same = SettingKey(
            rawKey = "reader.copy.enabled",
            valueType = Boolean::class,
            defaultValue = true,
            scope = SettingScope.DEBUG,
        )

        assertEquals(first, same)
        assertThrows(IllegalArgumentException::class.java) {
            SettingKey(
                rawKey = "reader.invalid",
                valueType = Int::class,
                defaultValue = -1,
                scope = SettingScope.USER,
                validate = { it >= 0 },
            )
        }
    }
}
