package com.xiyunmn.qdhelper.feature.readtime

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal class ReadTimeLocalStatisticsStore(context: Context) : AutoCloseable {
    private val context = context.applicationContext
    private val lock = Any()

    @Volatile
    private var auditImported = false

    private var helper: Database? = null
    private var closed = false
    private var mutation = 0L
    private var cached: CachedTotals? = null

    private data class CachedTotals(val userId: Long, val day: String, val week: String, val mutation: Long, val totals: Totals)

    data class Totals(
        val todaySeconds: Int,
        val weekSeconds: Int,
        val todayRecords: Int,
        val weekRecords: Int,
    )

    fun record(event: ReadTimeAuditEvent) {
        if (!event.daoSuccess) return
        synchronized(lock) {
            ensureAuditImported()
            val database = database().writableDatabase
            insert(database, event)
            prune(database, event.recordedAt)
        }
    }

    fun totals(userId: Long, now: Long = System.currentTimeMillis()): Totals = synchronized(lock) {
        require(userId >= 0L)
        ensureAuditImported()
        val todayKey = ReadTimeLocalStatisticsPolicy.dayKey(now)
        val weekStartKey = ReadTimeLocalStatisticsPolicy.weekStartKey(now)
        cached?.takeIf { it.userId == userId && it.day == todayKey && it.week == weekStartKey && it.mutation == mutation }
            ?.let { return it.totals }
        database().readableDatabase.rawQuery(
            """
            SELECT
                COALESCE(SUM(CASE WHEN day_key = ? THEN original_millis ELSE 0 END), 0),
                COALESCE(SUM(original_millis), 0),
                COALESCE(SUM(CASE WHEN day_key = ? THEN 1 ELSE 0 END), 0),
                COUNT(*)
            FROM $TABLE_SESSIONS
            WHERE user_id = ? AND day_key BETWEEN ? AND ?
            """.trimIndent(),
            arrayOf(todayKey, todayKey, userId.toString(), weekStartKey, todayKey),
        ).use { cursor ->
            check(cursor.moveToFirst()) { "Local read-time totals query returned no row" }
            Totals(
                todaySeconds = ReadTimeLocalStatisticsPolicy.millisToSeconds(cursor.getLong(0)),
                weekSeconds = ReadTimeLocalStatisticsPolicy.millisToSeconds(cursor.getLong(1)),
                todayRecords = cursor.getLong(2).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                weekRecords = cursor.getLong(3).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            ).also { cached = CachedTotals(userId, todayKey, weekStartKey, mutation, it) }
        }
    }

    override fun close() = synchronized(lock) {
        if (closed) return
        closed = true
        helper?.close()
        helper = null
        auditImported = false
        cached = null
    }

    private fun ensureAuditImported() {
        check(!closed) { "Read-time statistics store is closed" }
        if (auditImported) return
        val events = ReadTimeReportAuditStore.retainedEvents(context).filter(ReadTimeAuditEvent::daoSuccess)
        val database = database().writableDatabase
        database.beginTransaction()
        try {
            events.forEach { event -> insert(database, event) }
            prune(database, System.currentTimeMillis())
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        auditImported = true
    }

    private fun insert(database: SQLiteDatabase, event: ReadTimeAuditEvent) {
        val details = event.details
        val values = ContentValues().apply {
            put("event_key", eventKey(event))
            put("user_id", details.userId)
            put("recorded_at", event.recordedAt)
            put("day_key", ReadTimeLocalStatisticsPolicy.eventDayKey(event))
            put("original_millis", event.originalMillis.coerceAtLeast(0))
            put("persisted_millis", event.persistedMillis.coerceAtLeast(0))
            put("adjustment_applied", if (event.adjustmentApplied) 1 else 0)
            put("book_id", details.bookId)
            put("start_time", details.startTime)
            put("end_time", details.endTime)
        }
        if (database.insertWithOnConflict(TABLE_SESSIONS, null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L) mutation++
    }

    private fun prune(database: SQLiteDatabase, now: Long) {
        val retentionStart = ReadTimeLocalStatisticsPolicy.retentionStartKey(now, RETENTION_DAYS)
        if (database.delete(TABLE_SESSIONS, "day_key < ?", arrayOf(retentionStart)) > 0) mutation++
    }

    private fun eventKey(event: ReadTimeAuditEvent): String = buildString {
        // Keep legacy keys unchanged for idempotent import of unassigned pre-upgrade records.
        event.details.userId?.let { append("u").append(it).append(':') }
        append(event.recordedAt).append(':')
        append(event.details.bookId).append(':')
        append(event.details.startTime).append(':')
        append(event.details.endTime).append(':')
        append(event.persistedMillis)
    }

    private fun database(): Database {
        check(!closed) { "Read-time statistics store is closed" }
        return helper ?: Database(context).also { helper = it }
    }

    private class Database(context: Context) : SQLiteOpenHelper(
        context,
        DATABASE_NAME,
        null,
        DATABASE_VERSION,
    ) {
        override fun onCreate(database: SQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE $TABLE_SESSIONS (
                    event_key TEXT PRIMARY KEY NOT NULL,
                    user_id INTEGER,
                    recorded_at INTEGER NOT NULL,
                    day_key TEXT NOT NULL,
                    original_millis INTEGER NOT NULL,
                    persisted_millis INTEGER NOT NULL,
                    adjustment_applied INTEGER NOT NULL,
                    book_id INTEGER NOT NULL,
                    start_time INTEGER NOT NULL,
                    end_time INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            database.execSQL(
                "CREATE INDEX index_read_time_sessions_day_key ON $TABLE_SESSIONS(day_key)",
            )
            createAccountIndex(database)
        }

        override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                database.execSQL("ALTER TABLE $TABLE_SESSIONS ADD COLUMN user_id INTEGER")
                createAccountIndex(database)
            }
        }

        private fun createAccountIndex(database: SQLiteDatabase) {
            database.execSQL("CREATE INDEX index_read_time_sessions_user_day ON $TABLE_SESSIONS(user_id, day_key)")
        }
    }

    private companion object {
        const val DATABASE_NAME = "qdhelper_read_time.db"
        const val DATABASE_VERSION = 2
        const val RETENTION_DAYS = 35
        const val TABLE_SESSIONS = "read_time_sessions"
    }
}
