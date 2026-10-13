package com.xiyunmn.qdhelper.feature.readtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadTimeMultiplierPolicyTest {
    @Test
    fun parsesValidDecimalMultiplier() {
        assertEquals(1.0, ReadTimeMultiplierPolicy.parseMultiplier("1.0")!!, 1e-9)
        assertEquals(1.5, ReadTimeMultiplierPolicy.parseMultiplier("1.5")!!, 1e-9)
        assertEquals(2.5, ReadTimeMultiplierPolicy.parseMultiplier("2.5")!!, 1e-9)
        assertEquals(0.1, ReadTimeMultiplierPolicy.parseMultiplier("0.1")!!, 1e-9)
    }

    @Test
    fun trimsBeforeParsing() {
        assertEquals(2.0, ReadTimeMultiplierPolicy.parseMultiplier("  2.0  ")!!, 1e-9)
    }

    @Test
    fun rejectsBlankMalformedAndOutOfRange() {
        assertNull(ReadTimeMultiplierPolicy.parseMultiplier(null))
        assertNull(ReadTimeMultiplierPolicy.parseMultiplier(""))
        assertNull(ReadTimeMultiplierPolicy.parseMultiplier("   "))
        assertNull(ReadTimeMultiplierPolicy.parseMultiplier("abc"))
        assertNull(ReadTimeMultiplierPolicy.parseMultiplier("0"))
        assertNull(ReadTimeMultiplierPolicy.parseMultiplier("0.09"))
        assertNull(ReadTimeMultiplierPolicy.parseMultiplier("2.6"))
        assertNull(ReadTimeMultiplierPolicy.parseMultiplier("-1"))
        assertNull(ReadTimeMultiplierPolicy.parseMultiplier("NaN"))
    }

    @Test
    fun migratesLegacyMultiplierIntoCurrentRange() {
        assertEquals(2.5, ReadTimeMultiplierPolicy.migrateStoredMultiplier("5.0")!!, 0.0)
        assertEquals(0.1, ReadTimeMultiplierPolicy.migrateStoredMultiplier("0.05")!!, 0.0)
        assertNull(ReadTimeMultiplierPolicy.migrateStoredMultiplier("invalid"))
    }

    @Test
    fun scalesSessionByMultiplier() {
        assertEquals(600_000L, ReadTimeMultiplierPolicy.scaleSession(300_000L, 2.0))
        assertEquals(150_000L, ReadTimeMultiplierPolicy.scaleSession(300_000L, 0.5))
        assertEquals(300_000L, ReadTimeMultiplierPolicy.scaleSession(300_000L, 1.0))
    }

    @Test
    fun effectiveMultiplierKeepsConfiguredSliderSeparateFromAutomaticLimit() {
        assertEquals(2.02, ReadTimeMultiplierPolicy.effectiveMultiplier(2.5, 2.02), 0.0)
        assertEquals(1.8, ReadTimeMultiplierPolicy.effectiveMultiplier(1.8, 2.02), 0.0)
        assertEquals(2.5, ReadTimeMultiplierPolicy.effectiveMultiplier(2.5, null), 0.0)
    }

    @Test
    fun clampsToSessionCeiling() {
        // 8 minutes * 2.5 = 20 minutes, but the local safety ceiling is 10 minutes.
        // persisting more than that is impossible in the host's own logic and thus detectable.
        assertEquals(
            ReadTimeMultiplierPolicy.MAX_SESSION_MILLIS,
            ReadTimeMultiplierPolicy.scaleSession(480_000L, 2.5),
        )
    }

    @Test
    fun passesNonPositiveThroughUntouched() {
        assertEquals(0L, ReadTimeMultiplierPolicy.scaleSession(0L, 2.0))
        assertEquals(-1L, ReadTimeMultiplierPolicy.scaleSession(-1L, 2.0))
    }

    @Test
    fun describeScalingReportsRatioWithoutIdentifyingValues() {
        val detail = ReadTimeMultiplierPolicy.describeScaling(300_000L, 600_000L, 2.0)
        assertTrue(detail.contains("x2.0"))
        assertTrue(detail.contains("fromSec=300"))
        assertTrue(detail.contains("toSec=600"))
        assertFalse(detail.contains("clamped=true"))
    }

    @Test
    fun describeScalingFlagsClampedResults() {
        val detail = ReadTimeMultiplierPolicy.describeScaling(480_000L, ReadTimeMultiplierPolicy.MAX_SESSION_MILLIS, 2.5)
        assertTrue(detail.contains("clamped=true"))
    }
}
