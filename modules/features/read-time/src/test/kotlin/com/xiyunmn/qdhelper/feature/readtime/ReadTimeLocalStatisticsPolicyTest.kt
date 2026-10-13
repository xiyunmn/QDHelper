package com.xiyunmn.qdhelper.feature.readtime

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class ReadTimeLocalStatisticsPolicyTest {
    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun `week starts on Monday`() {
        val wednesday = timestamp("20260805 12:00:00")

        assertEquals("20260805", ReadTimeLocalStatisticsPolicy.dayKey(wednesday, utc))
        assertEquals("20260803", ReadTimeLocalStatisticsPolicy.weekStartKey(wednesday, utc))
    }

    @Test
    fun `retention includes current day and preceding thirty four days`() {
        val now = timestamp("20260805 12:00:00")

        assertEquals("20260702", ReadTimeLocalStatisticsPolicy.retentionStartKey(now, 35, utc))
    }

    @Test
    fun `host date key wins over wall clock fallback`() {
        val event = event(timestamp("20260805 12:00:00"), dateStart = "20260804")

        assertEquals("20260804", ReadTimeLocalStatisticsPolicy.eventDayKey(event, utc))
    }

    @Test
    fun `invalid host date key falls back to record timestamp`() {
        val event = event(timestamp("20260805 12:00:00"), dateStart = "2026-08-04")

        assertEquals("20260805", ReadTimeLocalStatisticsPolicy.eventDayKey(event, utc))
    }

    @Test
    fun `millisecond totals convert once after aggregation`() {
        assertEquals(3, ReadTimeLocalStatisticsPolicy.millisToSeconds(3_999))
        assertEquals(0, ReadTimeLocalStatisticsPolicy.millisToSeconds(-1))
        assertEquals(Int.MAX_VALUE, ReadTimeLocalStatisticsPolicy.millisToSeconds(Long.MAX_VALUE))
    }

    private fun timestamp(value: String): Long =
        SimpleDateFormat("yyyyMMdd HH:mm:ss", Locale.US).apply { timeZone = utc }.parse(value)!!.time

    private fun event(recordedAt: Long, dateStart: String?) = ReadTimeAuditEvent(
        recordedAt = recordedAt,
        originalMillis = 1_000,
        requestedMillis = 2_000,
        persistedMillis = 2_000,
        multiplier = 2.0,
        adjustmentRequested = true,
        adjustmentApplied = true,
        clamped = false,
        daoSuccess = true,
        daoError = null,
        scaleError = null,
        restoreFailed = false,
        encodedAfterDao = true,
        daoLatencyMillis = 1,
        details = ReadTimeEntityDetails(
            dateStart = dateStart,
            startTime = recordedAt - 1_000,
            endTime = recordedAt,
            bookId = 1,
            bookName = null,
            bookType = 1,
            startChapterId = 1,
            endChapterId = 1,
            chapterVip = 0,
            scrollMode = 0,
            unlockStatus = 0,
            unlockReason = 0,
            source = null,
            extraJson = null,
        ),
    )
}
