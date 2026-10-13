package com.xiyunmn.qdhelper.feature.readtime

internal data class ReadTimeReportKey(
    val bookId: Long,
    val endChapterId: Long,
    val startTime: Long,
    val endTime: Long,
    val totalMillis: Long,
)

internal data class ReadTimeEntityDetails(
    val dateStart: String?,
    val startTime: Long,
    val endTime: Long,
    val bookId: Long,
    val bookName: String?,
    val bookType: Int,
    val startChapterId: Long,
    val endChapterId: Long,
    val chapterVip: Int,
    val scrollMode: Int,
    val unlockStatus: Int,
    val unlockReason: Long,
    val source: String?,
    val extraJson: String?,
    val userId: Long? = null,
)

internal data class ReadTimeAuditEvent(
    val recordedAt: Long,
    val originalMillis: Long,
    val requestedMillis: Long,
    val persistedMillis: Long,
    val multiplier: Double?,
    val adjustmentRequested: Boolean,
    val adjustmentApplied: Boolean,
    val clamped: Boolean,
    val daoSuccess: Boolean,
    val daoError: String?,
    val scaleError: String?,
    val restoreFailed: Boolean,
    val encodedAfterDao: Boolean,
    val daoLatencyMillis: Long,
    val details: ReadTimeEntityDetails,
    val reportedAt: Long? = null,
) {
    fun reportKey(): ReadTimeReportKey = ReadTimeReportKey(
        bookId = details.bookId,
        endChapterId = details.endChapterId,
        startTime = details.startTime,
        endTime = details.endTime,
        totalMillis = persistedMillis,
    )
}

internal data class ReadTimeAuditCounters(
    val insertAttempts: Long = 0,
    val insertSuccesses: Long = 0,
    val adjustmentAttempts: Long = 0,
    val adjustmentInsertSuccesses: Long = 0,
    val clampedInsertSuccesses: Long = 0,
    val reportedRecords: Long = 0,
    val adjustedReportedRecords: Long = 0,
    val reportedOriginalMillis: Long = 0,
    val reportedMillis: Long = 0,
    val extraReportedMillis: Long = 0,
    val unmatchedReportItems: Long = 0,
)

internal data class ReadTimeAuditState(
    val todayKey: String = "",
    val today: ReadTimeAuditCounters = ReadTimeAuditCounters(),
    /** Process-local counters; the store deliberately does not persist these. */
    val session: ReadTimeAuditCounters = ReadTimeAuditCounters(),
    /** Oldest to newest. */
    val events: List<ReadTimeAuditEvent> = emptyList(),
    val droppedEvents: Long = 0,
)

internal object ReadTimeReportAuditPolicy {
    const val MAX_EVENTS = 50

    fun record(state: ReadTimeAuditState, todayKey: String, event: ReadTimeAuditEvent): ReadTimeAuditState {
        val aligned = alignToday(state, todayKey)
        val appended = aligned.events + event
        val overflow = (appended.size - MAX_EVENTS).coerceAtLeast(0)
        return aligned.copy(
            today = aligned.today.recordInsert(event),
            session = aligned.session.recordInsert(event),
            events = if (overflow == 0) appended else appended.drop(overflow),
            droppedEvents = aligned.droppedEvents + overflow,
        )
    }

    fun markReported(
        state: ReadTimeAuditState,
        todayKey: String,
        reportedAt: Long,
        keys: List<ReadTimeReportKey>,
    ): ReadTimeAuditState {
        var aligned = alignToday(state, todayKey)
        val events = aligned.events.toMutableList()
        var today = aligned.today
        var session = aligned.session
        keys.forEach { key ->
            val index = events.indexOfLast { event -> event.reportedAt == null && event.daoSuccess && event.reportKey() == key }
            if (index >= 0) {
                val event = events[index]
                events[index] = event.copy(reportedAt = reportedAt)
                today = today.recordReport(event)
                session = session.recordReport(event)
            } else if (events.none { event -> event.reportedAt != null && event.reportKey() == key }) {
                today = today.copy(unmatchedReportItems = today.unmatchedReportItems + 1)
                session = session.copy(unmatchedReportItems = session.unmatchedReportItems + 1)
            }
        }
        aligned = aligned.copy(today = today, session = session, events = events)
        return aligned
    }

    fun snapshotFor(state: ReadTimeAuditState, todayKey: String): ReadTimeAuditState = alignToday(state, todayKey)

    private fun alignToday(state: ReadTimeAuditState, todayKey: String): ReadTimeAuditState =
        if (state.todayKey == todayKey) {
            state
        } else {
            state.copy(
                todayKey = todayKey,
                today = ReadTimeAuditCounters(),
                events = emptyList(),
                droppedEvents = 0,
            )
        }

    private fun ReadTimeAuditCounters.recordInsert(event: ReadTimeAuditEvent): ReadTimeAuditCounters = copy(
        insertAttempts = insertAttempts + 1,
        insertSuccesses = insertSuccesses + if (event.daoSuccess) 1 else 0,
        adjustmentAttempts = adjustmentAttempts + if (event.adjustmentRequested) 1 else 0,
        adjustmentInsertSuccesses = adjustmentInsertSuccesses +
            if (event.daoSuccess && event.adjustmentApplied) 1 else 0,
        clampedInsertSuccesses = clampedInsertSuccesses +
            if (event.daoSuccess && event.adjustmentApplied && event.clamped) 1 else 0,
    )

    private fun ReadTimeAuditCounters.recordReport(event: ReadTimeAuditEvent): ReadTimeAuditCounters = copy(
        reportedRecords = reportedRecords + 1,
        adjustedReportedRecords = adjustedReportedRecords + if (event.adjustmentApplied) 1 else 0,
        reportedOriginalMillis = reportedOriginalMillis + event.originalMillis,
        reportedMillis = reportedMillis + event.persistedMillis,
        extraReportedMillis = extraReportedMillis + (event.persistedMillis - event.originalMillis),
    )
}
