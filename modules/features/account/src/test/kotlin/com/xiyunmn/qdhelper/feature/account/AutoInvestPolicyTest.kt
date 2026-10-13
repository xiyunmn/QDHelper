package com.xiyunmn.qdhelper.feature.account

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoInvestPolicyTest {
    @Test
    fun requiresServerConfirmedInvestmentEligibility() {
        assertEquals(AutoInvestEligibility.READY, AutoInvestPolicy.evaluate(details(600L, restCount = 1)))
        assertEquals(AutoInvestEligibility.NEED_MORE_READING, AutoInvestPolicy.evaluate(details(599L)))
        assertEquals(AutoInvestEligibility.NO_CAPACITY, AutoInvestPolicy.evaluate(details(900L, restCount = 0)))
        assertEquals(
            AutoInvestEligibility.ALREADY_INVESTED,
            AutoInvestPolicy.evaluate(details(900L, userStatus = 1)),
        )
        assertEquals(AutoInvestEligibility.CLOSED, AutoInvestPolicy.evaluate(details(900L, bookStatus = 2)))
        assertEquals(AutoInvestEligibility.INVALID, AutoInvestPolicy.evaluate(details(null)))
    }

    @Test
    fun reportedReadThresholdIsRequiredBeforeTheOnlyDetailQuery() {
        val tracker = AutoInvestStateTracker()

        assertNull(tracker.observeReportedRead(7L, 123L, 599_999L))
        val reservation = tracker.observeReportedRead(7L, 123L, 600_000L)

        assertEquals("reported-read-threshold", reservation?.reason)
        assertNull(tracker.observeReportedRead(7L, 123L, Long.MAX_VALUE))
    }

    @Test
    fun reportedReadRowsAreSummedSafely() {
        assertEquals(600_000L, AutoInvestPolicy.sumReportedReadMillis(listOf(120_000L, 480_000L)))
        assertEquals(10L, AutoInvestPolicy.sumReportedReadMillis(listOf(-5L, 10L)))
        assertEquals(Long.MAX_VALUE, AutoInvestPolicy.sumReportedReadMillis(listOf(Long.MAX_VALUE, 1L)))
    }

    @Test
    fun localDayKeyMatchesHostDatabaseFormat() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            assertEquals("20260811", AutoInvestPolicy.localDayKey(1_786_377_600_000L))
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun oneDetailResponseCanReserveAtMostOneInvestment() {
        val tracker = AutoInvestStateTracker()
        requireNotNull(tracker.observeReportedRead(7L, 123L, 600_000L))

        assertEquals(AutoInvestEligibility.READY, tracker.onDetails(7L, 123L, details(600L)))
        assertTrue(tracker.isInvestmentReserved(7L, 123L))
        assertNull(tracker.observeReportedRead(7L, 123L, Long.MAX_VALUE))
    }

    @Test
    fun queriedBooksRemainOneShotAfterStateEviction() {
        val tracker = AutoInvestStateTracker(maxTrackedBooks = 2)
        tracker.observeReportedRead(7L, 1L, 600_000L)
        tracker.onDetailFailure(7L, 1L)
        tracker.observeReportedRead(7L, 2L, 600_000L)
        tracker.onDetailFailure(7L, 2L)
        tracker.observeReportedRead(7L, 3L, 600_000L)
        tracker.onDetailFailure(7L, 3L)

        assertNull(tracker.observeReportedRead(7L, 1L, Long.MAX_VALUE))
    }

    @Test
    fun ineligibleAndFailedQueriesNeverRetryInTheSameProcess() {
        val noCapacity = AutoInvestStateTracker()
        noCapacity.observeReportedRead(7L, 123L, 600_000L)
        assertEquals(
            AutoInvestEligibility.NO_CAPACITY,
            noCapacity.onDetails(7L, 123L, details(700L, restCount = 0)),
        )
        assertNull(noCapacity.observeReportedRead(7L, 123L, Long.MAX_VALUE))

        val rejected = AutoInvestStateTracker()
        rejected.observeReportedRead(7L, 123L, 600_000L)
        rejected.onDetailFailure(7L, 123L)
        assertNull(rejected.observeReportedRead(7L, 123L, Long.MAX_VALUE))
    }

    @Test
    fun accountSwitchClearsOldReservations() {
        val tracker = AutoInvestStateTracker()
        tracker.observeReportedRead(7L, 123L, 600_000L)
        tracker.onDetails(7L, 123L, details(700L))
        assertTrue(tracker.isInvestmentReserved(7L, 123L))

        assertNotNull(tracker.observeReportedRead(8L, 123L, 600_000L))
        assertFalse(tracker.isInvestmentReserved(7L, 123L))
    }

    @Test
    fun onlyOneBookCanHoldAnInvestmentReservation() {
        val tracker = AutoInvestStateTracker()
        tracker.observeReportedRead(7L, 123L, 600_000L)
        tracker.onDetails(7L, 123L, details(700L))

        tracker.observeReportedRead(7L, 456L, 600_000L)
        assertEquals(
            AutoInvestEligibility.BUSY,
            tracker.onDetails(7L, 456L, details(700L).copy(bookId = 456L)),
        )
        assertFalse(tracker.isInvestmentReserved(7L, 456L))
    }

    private fun details(
        todayRead: Long?,
        restCount: Int = 1,
        userStatus: Int = 0,
        bookStatus: Int = 1,
    ) = AutoInvestDetailSnapshot(
        bookId = 123L,
        bookInvestStatus = bookStatus,
        userInvestStatus = userStatus,
        todayReadSeconds = todayRead,
        restCount = restCount,
    )
}
