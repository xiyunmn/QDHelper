package com.xiyunmn.qdhelper.feature.readtime

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal object ReadTimeLocalStatisticsPolicy {
    private val dayPattern = Regex("\\d{8}")

    fun eventDayKey(event: ReadTimeAuditEvent, timeZone: TimeZone = TimeZone.getDefault()): String =
        event.details.dateStart
            ?.trim()
            ?.takeIf(dayPattern::matches)
            ?: dayKey(event.recordedAt, timeZone)

    fun dayKey(timestamp: Long, timeZone: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).apply { this.timeZone = timeZone }.format(Date(timestamp))

    fun weekStartKey(timestamp: Long, timeZone: TimeZone = TimeZone.getDefault()): String {
        val calendar = Calendar.getInstance(timeZone).apply {
            timeInMillis = timestamp
            firstDayOfWeek = Calendar.MONDAY
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val daysSinceMonday = (calendar.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7
        calendar.add(Calendar.DAY_OF_MONTH, -daysSinceMonday)
        return dayKey(calendar.timeInMillis, timeZone)
    }

    fun retentionStartKey(
        timestamp: Long,
        retentionDays: Int,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): String {
        val calendar = Calendar.getInstance(timeZone).apply {
            timeInMillis = timestamp
            add(Calendar.DAY_OF_MONTH, -(retentionDays - 1).coerceAtLeast(0))
        }
        return dayKey(calendar.timeInMillis, timeZone)
    }

    fun millisToSeconds(millis: Long): Int =
        (millis.coerceAtLeast(0) / 1_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
