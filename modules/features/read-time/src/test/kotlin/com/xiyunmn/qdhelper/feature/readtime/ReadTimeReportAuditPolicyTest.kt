package com.xiyunmn.qdhelper.feature.readtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadTimeReportAuditPolicyTest {
    @Test
    fun `history rolls at fifty while today and session counters continue`() {
        var state = ReadTimeAuditState()
        repeat(55) { index ->
            state = ReadTimeReportAuditPolicy.record(state, "20260805", event(index.toLong()))
        }

        assertEquals(50, state.events.size)
        assertEquals(5, state.droppedEvents)
        assertEquals(55, state.today.insertAttempts)
        assertEquals(55, state.session.insertAttempts)
        assertEquals(5L, state.events.first().details.bookId)
    }

    @Test
    fun `network callback marks exact adjusted record once`() {
        val event = event(7, adjusted = true)
        val inserted = ReadTimeReportAuditPolicy.record(ReadTimeAuditState(), "20260805", event)

        val reported = ReadTimeReportAuditPolicy.markReported(
            inserted,
            todayKey = "20260805",
            reportedAt = 200,
            keys = listOf(event.reportKey()),
        )
        val duplicate = ReadTimeReportAuditPolicy.markReported(
            reported,
            todayKey = "20260805",
            reportedAt = 300,
            keys = listOf(event.reportKey()),
        )

        assertEquals(1, duplicate.today.reportedRecords)
        assertEquals(1, duplicate.session.reportedRecords)
        assertEquals(1, duplicate.today.adjustedReportedRecords)
        assertEquals(2_000, duplicate.today.reportedMillis)
        assertEquals(1_000, duplicate.today.extraReportedMillis)
        assertEquals(0, duplicate.today.unmatchedReportItems)
        assertEquals(200L, duplicate.events.single().reportedAt)
    }

    @Test
    fun `failed insert cannot be marked reported`() {
        val failed = event(8, daoSuccess = false)
        val inserted = ReadTimeReportAuditPolicy.record(ReadTimeAuditState(), "20260805", failed)

        val state = ReadTimeReportAuditPolicy.markReported(
            inserted,
            todayKey = "20260805",
            reportedAt = 200,
            keys = listOf(failed.reportKey()),
        )

        assertNull(state.events.single().reportedAt)
        assertEquals(0, state.today.reportedRecords)
        assertEquals(1, state.today.unmatchedReportItems)
    }

    @Test
    fun `new day clears today history while retaining current session counters`() {
        var state = ReadTimeReportAuditPolicy.record(ReadTimeAuditState(), "20260805", event(1))
        repeat(50) { index ->
            state = ReadTimeReportAuditPolicy.record(state, "20260805", event(index.toLong() + 2))
        }

        val nextDay = ReadTimeReportAuditPolicy.snapshotFor(state, "20260806")

        assertEquals(0, nextDay.today.insertAttempts)
        assertEquals(51, nextDay.session.insertAttempts)
        assertEquals(0, nextDay.events.size)
        assertEquals(0, nextDay.droppedEvents)
    }

    private fun event(
        id: Long,
        adjusted: Boolean = false,
        daoSuccess: Boolean = true,
    ): ReadTimeAuditEvent = ReadTimeAuditEvent(
        recordedAt = 100 + id,
        originalMillis = 1_000,
        requestedMillis = if (adjusted) 2_000 else 1_000,
        persistedMillis = if (adjusted) 2_000 else 1_000,
        multiplier = if (adjusted) 2.0 else null,
        adjustmentRequested = adjusted,
        adjustmentApplied = adjusted,
        clamped = false,
        daoSuccess = daoSuccess,
        daoError = null,
        scaleError = null,
        restoreFailed = false,
        encodedAfterDao = true,
        daoLatencyMillis = 2,
        details = ReadTimeEntityDetails(
            dateStart = "20260805",
            startTime = id * 10,
            endTime = id * 10 + 5,
            bookId = id,
            bookName = "Book $id",
            bookType = 1,
            startChapterId = id,
            endChapterId = id,
            chapterVip = 0,
            scrollMode = 1,
            unlockStatus = 0,
            unlockReason = 0,
            source = null,
            extraJson = null,
        ),
    )
}
