package com.xiyunmn.qdhelper.feature.readtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadTimeCloudPoliciesTest {
    @Test
    fun `cloud response reader bypasses display getters`() {
        val fixture = HookedReadInfoFixture()
        val totals = ReadTimeCloudResponseReader.read(fixture) { instance, fieldName ->
            (instance as HookedReadInfoFixture).rawField(fieldName)
        }

        assertEquals(7_200, totals?.todaySeconds)
        assertEquals(36_000, totals?.weekSeconds)
        assertEquals(0, fixture.getterCalls)
    }

    @Test
    fun `cloud response reader rejects incompatible response`() {
        assertNull(ReadTimeCloudResponseReader.read(IncompatibleReadInfoFixture()) { instance, fieldName ->
            (instance as IncompatibleReadInfoFixture).rawField(fieldName)
        })
    }

    @Test
    fun `disabled multiplier is reported without activating configured value`() {
        val state = ReadTimeCloudPresentationPolicy.multiplierState(enabled = false, raw = "2.0")

        assertEquals("已关闭（设定 2.0x）", state.label)
        assertNull(state.activeMultiplier)
    }

    @Test
    fun `enabled multiplier state remains available for cloud diagnostics`() {
        val state = ReadTimeCloudPresentationPolicy.multiplierState(
            enabled = true,
            raw = "2.5",
            automaticLimitRaw = "2.026",
        )

        assertEquals("设定 2.5x · 实际 2.026x", state.label)
        assertEquals(2.026, state.activeMultiplier!!, 0.0)
    }

    private class HookedReadInfoFixture {
        var getterCalls: Int = 0
            private set

        @Suppress("unused")
        fun getTodayReadTime(): Int {
            getterCalls++
            return 3_600
        }

        @Suppress("unused")
        fun getWeekReadTime(): Int {
            getterCalls++
            return 18_000
        }

        fun rawField(name: String): Int? = when (name) {
            "TodayReadTime" -> 7_200
            "WeekReadTime" -> 36_000
            else -> null
        }
    }

    private class IncompatibleReadInfoFixture {
        fun rawField(name: String): Int? = if (name == "TodayReadTime") 7_200 else null
    }
}
