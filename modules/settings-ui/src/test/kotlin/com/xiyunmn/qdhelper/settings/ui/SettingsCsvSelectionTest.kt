package com.xiyunmn.qdhelper.settings.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsCsvSelectionTest {
    private val keys = listOf("member", "banner", "collection")

    @Test
    fun sequentialUpdatesPreserveEarlierSelections() {
        val first = updateSettingsCsvSelection(null, keys, key = "member", selected = true)
        val second = updateSettingsCsvSelection(first.serialized, keys, key = "banner", selected = true)

        assertEquals(linkedSetOf("member", "banner"), second.keys)
        assertEquals("member,banner", second.serialized)
    }

    @Test
    fun updateUsesDefaultsOnlyBeforeAValueHasBeenStored() {
        val defaults = setOf("member", "banner")
        val first = updateSettingsCsvSelection(
            raw = null,
            validOrder = keys,
            defaultKeys = defaults,
            key = "member",
            selected = false,
        )
        val second = updateSettingsCsvSelection(
            raw = first.serialized,
            validOrder = keys,
            defaultKeys = defaults,
            key = "collection",
            selected = true,
        )

        assertEquals("banner,collection", second.serialized)
    }

    @Test
    fun invalidAndDuplicateKeysAreDiscardedInStableOrder() {
        val selected = readSettingsCsvSelection(
            raw = "collection,unknown,member,collection",
            validOrder = keys,
        )

        assertEquals(linkedSetOf("member", "collection"), selected)
    }
}
