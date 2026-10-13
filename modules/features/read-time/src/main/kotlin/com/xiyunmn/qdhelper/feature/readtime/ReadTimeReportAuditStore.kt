package com.xiyunmn.qdhelper.feature.readtime

import android.content.Context
import com.xiyunmn.qdhelper.config.SettingsRuntime
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object ReadTimeReportAuditStore {
    private const val VERSION = 3
    private const val STATE_VERSION = 2
    private val lock = Any()

    private val sessionCounters = mutableMapOf<Long?, ReadTimeAuditCounters>()

    @Volatile
    private var mutation = 0L

    fun record(context: Context, event: ReadTimeAuditEvent) {
        synchronized(lock) {
            val states = readStates(context)
            val userId = event.details.userId
            val state = withSession(userId, states[userId] ?: ReadTimeAuditState()).let {
                if (userId == null && it.todayKey != dateKey(event.recordedAt)) {
                    // Unassigned history is retained; a new account never inherits or clears it.
                    it.copy(todayKey = dateKey(event.recordedAt), today = ReadTimeAuditCounters())
                } else it
            }
            val next = ReadTimeReportAuditPolicy.record(state, dateKey(event.recordedAt), event)
            states[userId] = next
            if (writeStates(context, states)) sessionCounters[userId] = next.session
        }
    }

    fun markReported(context: Context, keys: List<ReadTimeReportKey>, reportedAt: Long): Set<Long> = synchronized(lock) {
        if (keys.isEmpty()) return emptySet()
        val states = readStates(context)
        val grouped = linkedMapOf<Long?, MutableList<ReadTimeReportKey>>()
        keys.distinct().forEach { key ->
            // ReportEntity has no UserID. Never assign a late callback to the currently logged-in user.
            val matches = states.flatMap { (userId, state) ->
                state.events.filter { it.daoSuccess && it.reportKey() == key }.map { userId to it }
            }
            matches.singleOrNull()?.takeIf { it.second.reportedAt == null }?.let { (userId, _) ->
                grouped.getOrPut(userId) { mutableListOf() }.add(key)
            }
        }
        if (grouped.isEmpty()) return emptySet()
        grouped.forEach { (userId, matched) ->
            states[userId] = ReadTimeReportAuditPolicy.markReported(
                withSession(userId, states.getValue(userId)),
                if (userId == null) states.getValue(userId).todayKey else dateKey(reportedAt),
                reportedAt, matched,
            )
        }
        if (!writeStates(context, states)) return emptySet()
        grouped.keys.forEach { sessionCounters[it] = states.getValue(it).session }
        grouped.keys.filterNotNull().toSet()
    }

    fun snapshot(context: Context, userId: Long?, now: Long = System.currentTimeMillis()): ReadTimeAuditState = synchronized(lock) {
        if (userId == null) return ReadTimeAuditState()
        val states = readStates(context)
        val persisted = states[userId] ?: ReadTimeAuditState()
        val current = withSession(userId, persisted)
        val aligned = ReadTimeReportAuditPolicy.snapshotFor(current, dateKey(now))
        if (persisted.todayKey != aligned.todayKey) {
            states[userId] = aligned
            writeStates(context, states)
        }
        aligned
    }

    fun clear(context: Context, userId: Long): Boolean = synchronized(lock) {
        val states = readStates(context)
        states.remove(userId)
        writeStates(context, states).also { if (it) sessionCounters.remove(userId) }
    }

    fun mutationSignature(): Long = mutation

    fun clearSessionCounters() = synchronized(lock) { sessionCounters.clear() }

    fun retainedEvents(context: Context): List<ReadTimeAuditEvent> = synchronized(lock) {
        readStates(context).values.flatMap { it.events }
    }

    private fun withSession(userId: Long?, state: ReadTimeAuditState): ReadTimeAuditState =
        state.copy(session = sessionCounters[userId] ?: ReadTimeAuditCounters())

    private fun readStates(context: Context): MutableMap<Long?, ReadTimeAuditState> {
        val raw = SettingsRuntime.readTimeAuditState(context.applicationContext)
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return linkedMapOf()
        if (json.optInt("version") in 1..STATE_VERSION) {
            val states = linkedMapOf<Long?, ReadTimeAuditState>(null to decodeState(json, null))
            writeStates(context, states)
            return states
        }
        if (json.optInt("version") != VERSION) return linkedMapOf()
        return linkedMapOf<Long?, ReadTimeAuditState>().apply {
            json.optJSONObject("unassigned")?.let { put(null, decodeState(it, null)) }
            json.optJSONObject("accounts")?.let { accounts ->
                accounts.keys().forEach { key ->
                    val userId = key.toLongOrNull()?.takeIf { it >= 0L } ?: return@forEach
                    accounts.optJSONObject(key)?.let { put(userId, decodeState(it, userId)) }
                }
            }
        }
    }

    private fun writeStates(context: Context, states: Map<Long?, ReadTimeAuditState>): Boolean {
        val accounts = JSONObject()
        states.forEach { (userId, state) -> if (userId != null) accounts.put(userId.toString(), encodeState(state)) }
        val json = JSONObject().put("version", VERSION).put("accounts", accounts)
            .put("unassigned", states[null]?.let(::encodeState) ?: JSONObject.NULL)
        return SettingsRuntime.putReadTimeAuditState(context.applicationContext, json.toString()).also { if (it) mutation++ }
    }

    private fun dateKey(timestamp: Long): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(timestamp))

    private fun encodeState(state: ReadTimeAuditState): JSONObject = JSONObject()
        .put("version", STATE_VERSION)
        .put("todayKey", state.todayKey)
        .put("today", encodeCounters(state.today))
        .put("droppedEvents", state.droppedEvents)
        .put("events", JSONArray().apply { state.events.forEach { put(encodeEvent(it)) } })

    private fun decodeState(json: JSONObject, userId: Long?): ReadTimeAuditState {
        if (json.optInt("version") !in 1..STATE_VERSION) return ReadTimeAuditState()
        val eventsJson = json.optJSONArray("events") ?: JSONArray()
        val events = buildList {
            for (index in 0 until eventsJson.length()) {
                eventsJson.optJSONObject(index)?.let { eventJson ->
                    val event = decodeEvent(eventJson)
                    add(event.copy(details = event.details.copy(userId = userId)))
                }
            }
        }.takeLast(ReadTimeReportAuditPolicy.MAX_EVENTS)
        return ReadTimeAuditState(
            todayKey = json.optString("todayKey"),
            today = decodeCounters(json.optJSONObject("today")),
            events = events,
            droppedEvents = json.optLong("droppedEvents") + (eventsJson.length() - events.size).coerceAtLeast(0),
        )
    }

    private fun encodeCounters(value: ReadTimeAuditCounters): JSONObject = JSONObject()
        .put("insertAttempts", value.insertAttempts)
        .put("insertSuccesses", value.insertSuccesses)
        .put("adjustmentAttempts", value.adjustmentAttempts)
        .put("adjustmentInsertSuccesses", value.adjustmentInsertSuccesses)
        .put("clampedInsertSuccesses", value.clampedInsertSuccesses)
        .put("reportedRecords", value.reportedRecords)
        .put("adjustedReportedRecords", value.adjustedReportedRecords)
        .put("reportedOriginalMillis", value.reportedOriginalMillis)
        .put("reportedMillis", value.reportedMillis)
        .put("extraReportedMillis", value.extraReportedMillis)
        .put("unmatchedReportItems", value.unmatchedReportItems)

    private fun decodeCounters(json: JSONObject?): ReadTimeAuditCounters = ReadTimeAuditCounters(
        insertAttempts = json?.optLong("insertAttempts") ?: 0,
        insertSuccesses = json?.optLong("insertSuccesses") ?: 0,
        adjustmentAttempts = json?.optLong("adjustmentAttempts") ?: 0,
        adjustmentInsertSuccesses = json?.optLong("adjustmentInsertSuccesses") ?: 0,
        clampedInsertSuccesses = json?.optLong("clampedInsertSuccesses") ?: 0,
        reportedRecords = json?.optLong("reportedRecords") ?: 0,
        adjustedReportedRecords = json?.optLong("adjustedReportedRecords") ?: 0,
        reportedOriginalMillis = json?.optLong("reportedOriginalMillis") ?: 0,
        reportedMillis = json?.optLong("reportedMillis") ?: 0,
        extraReportedMillis = json?.optLong("extraReportedMillis") ?: 0,
        unmatchedReportItems = json?.optLong("unmatchedReportItems") ?: 0,
    )

    private fun encodeEvent(event: ReadTimeAuditEvent): JSONObject = JSONObject()
        .put("recordedAt", event.recordedAt)
        .put("originalMillis", event.originalMillis)
        .put("requestedMillis", event.requestedMillis)
        .put("persistedMillis", event.persistedMillis)
        .put("multiplier", event.multiplier)
        .put("adjustmentRequested", event.adjustmentRequested)
        .put("adjustmentApplied", event.adjustmentApplied)
        .put("clamped", event.clamped)
        .put("daoSuccess", event.daoSuccess)
        .putNullable("daoError", event.daoError)
        .putNullable("scaleError", event.scaleError)
        .put("restoreFailed", event.restoreFailed)
        .put("encodedAfterDao", event.encodedAfterDao)
        .put("daoLatencyMillis", event.daoLatencyMillis)
        .putNullable("reportedAt", event.reportedAt)
        .put("details", encodeDetails(event.details))

    private fun decodeEvent(json: JSONObject): ReadTimeAuditEvent = ReadTimeAuditEvent(
        recordedAt = json.optLong("recordedAt"),
        originalMillis = json.optLong("originalMillis"),
        requestedMillis = json.optLong("requestedMillis"),
        persistedMillis = json.optLong("persistedMillis"),
        multiplier = json.optDouble("multiplier").takeUnless { it.isNaN() || !json.has("multiplier") },
        adjustmentRequested = json.optBoolean("adjustmentRequested"),
        adjustmentApplied = json.optBoolean("adjustmentApplied"),
        clamped = json.optBoolean("clamped"),
        daoSuccess = json.optBoolean("daoSuccess"),
        daoError = json.optNullableString("daoError"),
        scaleError = json.optNullableString("scaleError"),
        restoreFailed = json.optBoolean("restoreFailed"),
        encodedAfterDao = json.optBoolean("encodedAfterDao"),
        daoLatencyMillis = json.optLong("daoLatencyMillis"),
        details = decodeDetails(json.optJSONObject("details") ?: JSONObject()),
        reportedAt = json.optNullableLong("reportedAt"),
    )

    private fun encodeDetails(value: ReadTimeEntityDetails): JSONObject = JSONObject()
        .putNullable("userId", value.userId)
        .putNullable("dateStart", value.dateStart)
        .put("startTime", value.startTime)
        .put("endTime", value.endTime)
        .put("bookId", value.bookId)
        .putNullable("bookName", value.bookName)
        .put("bookType", value.bookType)
        .put("startChapterId", value.startChapterId)
        .put("endChapterId", value.endChapterId)
        .put("chapterVip", value.chapterVip)
        .put("scrollMode", value.scrollMode)
        .put("unlockStatus", value.unlockStatus)
        .put("unlockReason", value.unlockReason)
        .putNullable("source", value.source)
        .putNullable("extraJson", value.extraJson)

    private fun decodeDetails(json: JSONObject): ReadTimeEntityDetails = ReadTimeEntityDetails(
        userId = json.optNullableLong("userId"),
        dateStart = json.optNullableString("dateStart"),
        startTime = json.optLong("startTime"),
        endTime = json.optLong("endTime"),
        bookId = json.optLong("bookId"),
        bookName = json.optNullableString("bookName"),
        bookType = json.optInt("bookType"),
        startChapterId = json.optLong("startChapterId"),
        endChapterId = json.optLong("endChapterId"),
        chapterVip = json.optInt("chapterVip"),
        scrollMode = json.optInt("scrollMode"),
        unlockStatus = json.optInt("unlockStatus"),
        unlockReason = json.optLong("unlockReason"),
        source = json.optNullableString("source"),
        extraJson = json.optNullableString("extraJson"),
    )

    private fun JSONObject.putNullable(name: String, value: Any?): JSONObject = put(name, value ?: JSONObject.NULL)

    private fun JSONObject.optNullableString(name: String): String? =
        takeIf { has(name) && !isNull(name) }?.optString(name)

    private fun JSONObject.optNullableLong(name: String): Long? =
        takeIf { has(name) && !isNull(name) }?.optLong(name)
}
