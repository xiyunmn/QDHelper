package com.xiyunmn.qdhelper.settings.ui.compose.host

import org.junit.Assert.assertEquals
import org.junit.Test

class DisclaimerReadTimerTest {
    @Test
    fun confirmationNeedsTenSecondsOfVisibleReading() {
        val timer = DisclaimerReadTimer()
        assertEquals(10, timer.update(0, true))
        for (time in 100L..9900L step 100) timer.update(time, true)
        assertEquals(1, timer.update(9999, true))
        assertEquals(0, timer.update(10000, true))
    }

    @Test
    fun backgroundAndUnobservedTimeDoNotCompleteReading() {
        val timer = DisclaimerReadTimer()
        timer.update(0, true)
        timer.update(100, false)
        assertEquals(10, timer.update(60000, false))
        assertEquals(10, timer.update(60100, true))
        // A stalled UI or suspended device cannot finish the timer in one jump.
        assertEquals(10, timer.update(120000, true))
    }
}
