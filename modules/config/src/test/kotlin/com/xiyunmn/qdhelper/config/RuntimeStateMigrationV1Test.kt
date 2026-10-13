package com.xiyunmn.qdhelper.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeStateMigrationV1Test {
    @Test
    fun migratesAllValuesThenDeletesLegacyAndMarksComplete() {
        val timeline = mutableListOf<String>()
        val legacy = FakeStore(
            mutableMapOf(
                "runtime.page_custom.pending_scan" to "mine",
                "runtime.account.auto_sign_in.last_user" to 42L,
            ),
            events = timeline,
        )
        val runtime = FakeStore(events = timeline)

        assertTrue(RuntimeStateMigrationV1.migrate(legacy, runtime))
        assertEquals("mine", runtime.values["runtime.page_custom.pending_scan"])
        assertEquals(42L, runtime.values["runtime.account.auto_sign_in.last_user"])
        assertTrue(runtime.values[RuntimeStateMigrationV1.MARKER] as Boolean)
        assertTrue(legacy.values.isEmpty())
        assertTrue(timeline.indexOf("remove") > timeline.indexOf("write:runtime.account.auto_sign_in.last_user"))
        assertTrue(timeline.indexOf("write:${RuntimeStateMigrationV1.MARKER}") > timeline.indexOf("remove"))
    }

    @Test
    fun existingRuntimeValueWinsAndMigrationIsIdempotent() {
        val legacy = FakeStore(mutableMapOf("runtime.page_custom.pending_scan" to "legacy"))
        val runtime = FakeStore(mutableMapOf("runtime.page_custom.pending_scan" to "new"))

        assertTrue(RuntimeStateMigrationV1.migrate(legacy, runtime))
        assertEquals("new", runtime.values["runtime.page_custom.pending_scan"])
        val eventsAfterFirstRun = runtime.events.toList()
        assertTrue(RuntimeStateMigrationV1.migrate(legacy, runtime))
        assertEquals(eventsAfterFirstRun, runtime.events)
    }

    @Test
    fun failedCopyKeepsLegacyAndDoesNotWriteMarker() {
        val legacy = FakeStore(mutableMapOf("runtime.page_custom.pending_scan" to "legacy"))
        val runtime = FakeStore(failWrites = mutableSetOf("runtime.page_custom.pending_scan"))

        assertFalse(RuntimeStateMigrationV1.migrate(legacy, runtime))
        assertTrue(legacy.contains("runtime.page_custom.pending_scan"))
        assertFalse(runtime.contains(RuntimeStateMigrationV1.MARKER))
    }

    @Test
    fun failedDeletionDoesNotWriteMarker() {
        val legacy = FakeStore(
            values = mutableMapOf("runtime.page_custom.pending_scan" to "legacy"),
            failRemove = true,
        )
        val runtime = FakeStore()

        assertFalse(RuntimeStateMigrationV1.migrate(legacy, runtime))
        assertTrue(legacy.contains("runtime.page_custom.pending_scan"))
        assertFalse(runtime.contains(RuntimeStateMigrationV1.MARKER))
    }

    private class FakeStore(
        val values: MutableMap<String, Any> = mutableMapOf(),
        private val failWrites: MutableSet<String> = mutableSetOf(),
        private val failRemove: Boolean = false,
        val events: MutableList<String> = mutableListOf(),
    ) : MigrationStore {
        override fun contains(rawKey: String): Boolean = rawKey in values
        override fun readBoolean(rawKey: String, defaultValue: Boolean): Boolean = values[rawKey] as? Boolean ?: defaultValue
        override fun readString(rawKey: String, defaultValue: String): String = values[rawKey] as? String ?: defaultValue
        override fun readLong(rawKey: String, defaultValue: Long): Long = values[rawKey] as? Long ?: defaultValue
        override fun writeBoolean(rawKey: String, value: Boolean): Boolean = write(rawKey, value)
        override fun writeString(rawKey: String, value: String): Boolean = write(rawKey, value)
        override fun writeLong(rawKey: String, value: Long): Boolean = write(rawKey, value)
        override fun removeAll(rawKeys: Collection<String>): Boolean {
            events += "remove"
            if (failRemove) return false
            rawKeys.forEach(values::remove)
            return true
        }

        private fun write(rawKey: String, value: Any): Boolean {
            events += "write:$rawKey"
            if (rawKey in failWrites) return false
            values[rawKey] = value
            return true
        }
    }
}
