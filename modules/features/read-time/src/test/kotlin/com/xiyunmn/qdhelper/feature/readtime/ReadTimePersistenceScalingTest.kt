package com.xiyunmn.qdhelper.feature.readtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadTimePersistenceScalingTest {
    @Test
    fun `successful persistence restores entity and records success`() {
        val fixture = Fixture()

        val result = fixture.execute { true }

        assertEquals(true, result)
        assertEquals(listOf(2_000L, 1_000L), fixture.writes)
        assertTrue(fixture.scaleApplied)
        assertTrue(fixture.persisted)
        assertFalse(fixture.restoreFailed)
    }

    @Test
    fun `failed persistence restores entity without recording success`() {
        val fixture = Fixture()

        val result = fixture.execute { false }

        assertEquals(false, result)
        assertEquals(listOf(2_000L, 1_000L), fixture.writes)
        assertFalse(fixture.persisted)
    }

    @Test
    fun `persistence exception is preserved after restoring entity`() {
        val fixture = Fixture()
        val failure = IllegalStateException("insert failed")

        val thrown = runCatching { fixture.execute { throw failure } }.exceptionOrNull()

        assertSame(failure, thrown)
        assertEquals(listOf(2_000L, 1_000L), fixture.writes)
        assertFalse(fixture.persisted)
    }

    @Test
    fun `restore failure does not replace persistence result`() {
        val fixture = Fixture(restoreSucceeds = false)

        val result = fixture.execute { true }

        assertEquals(true, result)
        assertTrue(fixture.persisted)
        assertTrue(fixture.restoreFailed)
    }

    @Test
    fun `scale write exception falls back to unmodified persistence`() {
        val fixture = Fixture(scaleFailure = IllegalAccessException("write failed"))
        var persistCalls = 0

        val result = fixture.execute {
            persistCalls++
            true
        }

        assertEquals(true, result)
        assertEquals(1, persistCalls)
        assertFalse(fixture.persisted)
        assertFalse(fixture.scaleApplied)
        assertTrue(fixture.scaleFailed)
        assertFalse(fixture.restoreFailed)
    }

    @Test
    fun `related state restore still runs when read time restore fails`() {
        val fixture = Fixture(restoreSucceeds = false)

        fixture.execute { true }

        assertTrue(fixture.relatedRestoreCalled)
        assertTrue(fixture.restoreFailed)
    }

    @Test
    fun `related state restore failure is reported`() {
        val fixture = Fixture(relatedRestoreSucceeds = false)

        val result = fixture.execute { true }

        assertEquals(true, result)
        assertTrue(fixture.relatedRestoreCalled)
        assertTrue(fixture.restoreFailed)
    }

    private class Fixture(
        private val restoreSucceeds: Boolean = true,
        private val scaleFailure: Throwable? = null,
        private val relatedRestoreSucceeds: Boolean = true,
    ) {
        val writes = mutableListOf<Long>()
        var persisted = false
        var scaleApplied = false
        var scaleFailed = false
        var relatedRestoreCalled = false
        var restoreFailed = false

        fun execute(persist: () -> Any?): Any? = ReadTimePersistenceScaling.proceed(
            original = 1_000L,
            scaled = 2_000L,
            write = { value ->
                if (value == 2_000L) scaleFailure?.let { throw it }
                writes += value
                value != 1_000L || restoreSucceeds
            },
            persist = persist,
            onPersisted = { persisted = true },
            onScaleApplied = { scaleApplied = true },
            onScaleFailed = { scaleFailed = true },
            restoreRelatedState = {
                relatedRestoreCalled = true
                relatedRestoreSucceeds
            },
            onRestoreFailed = { restoreFailed = true },
        )
    }
}
