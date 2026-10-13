package com.xiyunmn.qdhelper.feature.readtime

import android.content.Context
import com.xiyunmn.qdhelper.config.ConfigRuntime
import com.xiyunmn.qdhelper.config.SettingIds
import com.xiyunmn.qdhelper.config.SettingsRuntime
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ReadTimeAccountStoresTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val now = System.currentTimeMillis()
    private lateinit var store: ReadTimeLocalStatisticsStore

    @Before fun setUp() {
        ConfigRuntime.clear()
        ConfigRuntime.initialize(context)
        SettingsRuntime.putBoolean(context, SettingIds.KEY_MODULE_ENABLED, true)
        SettingsRuntime.putBoolean(context, SettingIds.KEY_READ_TIME_MULTIPLIER_ENABLED, true)
        SettingsRuntime.putReadTimeMultiplier(context, "2.0")
        ReadTimeReportAuditStore.clearSessionCounters()
        store = ReadTimeLocalStatisticsStore(context)
    }

    @After fun tearDown() {
        store.close()
        ReadTimeReportAuditStore.clearSessionCounters()
        ConfigRuntime.clear()
    }

    @Test fun accountsGuestsAndUnassignedRowsStaySeparateAcrossWritesAndReopening() {
        val a = readTimeEvent(11, now, original = 1_999)
        val b = a.copy(originalMillis = 8_000, details = a.details.copy(userId = 22))
        listOf(a, b, readTimeEvent(0, now, book = 2, original = 5_000), readTimeEvent(null, now, book = 3, original = 7_000)).forEach(store::record)
        assertEquals(1, store.totals(11, now).todaySeconds)
        assertEquals(8, store.totals(22, now).todaySeconds)
        assertEquals(1, store.totals(11, now).todaySeconds)
        assertEquals(5, store.totals(0, now).todaySeconds)
        store.record(a)
        store.record(readTimeEvent(11, now, book = 4, original = 2_101))
        assertEquals(4, store.totals(11, now).todaySeconds)
        assertEquals(2, store.totals(11, now).todayRecords)
        assertEquals(8, store.totals(22, now).todaySeconds)

        store.close()
        assertThrows(IllegalStateException::class.java) { store.totals(11, now) }
        assertThrows(IllegalStateException::class.java) { store.record(a) }
        ReadTimeLocalStatisticsStore(context).use { reopened ->
            assertEquals(4, reopened.totals(11, now).todaySeconds)
            assertEquals(8, reopened.totals(22, now).todaySeconds)
        }
    }

    @Test fun databaseAndAuditMigrationKeepLegacyRowsUnassignedWithoutImportingThemTwice() {
        val legacy = readTimeEvent(null, now)
        context.openOrCreateDatabase("qdhelper_read_time.db", Context.MODE_PRIVATE, null).use { database ->
            database.execSQL("""
                CREATE TABLE read_time_sessions (
                    event_key TEXT PRIMARY KEY NOT NULL, recorded_at INTEGER NOT NULL, day_key TEXT NOT NULL,
                    original_millis INTEGER NOT NULL, persisted_millis INTEGER NOT NULL, adjustment_applied INTEGER NOT NULL,
                    book_id INTEGER NOT NULL, start_time INTEGER NOT NULL, end_time INTEGER NOT NULL
                )
            """.trimIndent())
            database.execSQL("CREATE INDEX index_read_time_sessions_day_key ON read_time_sessions(day_key)")
            database.execSQL("INSERT INTO read_time_sessions VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", arrayOf<Any>(
                "$now:1:$now:${now + 60_000}:120000", now, ReadTimeLocalStatisticsPolicy.dayKey(now), 60_000, 120_000, 1, 1, now, now + 60_000,
            ))
            database.version = 1
        }
        val legacyJson = JSONObject().put("version", 2).put("todayKey", ReadTimeLocalStatisticsPolicy.dayKey(now))
            .put("today", JSONObject().put("insertSuccesses", 1))
            .put("events", JSONArray().put(JSONObject()
                .put("recordedAt", now).put("originalMillis", 60_000).put("persistedMillis", 120_000).put("daoSuccess", true)
                .put("details", JSONObject().put("bookId", 1).put("startTime", now).put("endTime", now + 60_000)
                    .put("dateStart", ReadTimeLocalStatisticsPolicy.dayKey(now)))))
        SettingsRuntime.putReadTimeAuditState(context, legacyJson.toString())

        assertEquals(0, store.totals(11, now).weekRecords)
        assertEquals(0, store.totals(0, now).weekRecords)
        assertNull(ReadTimeReportAuditStore.retainedEvents(context).single().details.userId)
        store.record(legacy.copy(details = legacy.details.copy(userId = 11)))
        assertEquals(60, store.totals(11, now).todaySeconds)
        context.openOrCreateDatabase("qdhelper_read_time.db", Context.MODE_PRIVATE, null).use { database ->
            assertEquals(2, database.version)
            database.rawQuery("SELECT user_id, original_millis FROM read_time_sessions WHERE user_id IS NULL", null).use { rows ->
                assertEquals(1, rows.count)
                assertTrue(rows.moveToFirst())
                assertTrue(rows.isNull(0))
                assertEquals(60_000L, rows.getLong(1))
            }
        }
        ReadTimeReportAuditStore.record(context, legacy.copy(recordedAt = now + 86_400_000))
        assertEquals(2, ReadTimeReportAuditStore.retainedEvents(context).size)
        assertEquals(0, ReadTimeReportAuditStore.snapshot(context, 11, now).today.insertSuccesses)
    }

    @Test fun totalsRollAcrossMidnightAndReuseTheRecordedAccountAfterReturningToIt() {
        val tomorrow = now + 86_400_000
        store.record(readTimeEvent(11, now, original = 3_000))
        store.record(readTimeEvent(11, tomorrow, original = 5_000))
        assertEquals(3, store.totals(11, now).todaySeconds)
        assertEquals(5, store.totals(11, tomorrow).todaySeconds)
        assertEquals(0, store.totals(22, tomorrow).weekSeconds)
        assertEquals(5, store.totals(11, tomorrow).todaySeconds)
    }

    @Test fun reportsRequireOneExactEventAndDuplicateCallbacksDoNotCountAgain() {
        val a = readTimeEvent(11, now)
        val b = a.copy(details = a.details.copy(userId = 22))
        ReadTimeReportAuditStore.record(context, a)
        ReadTimeReportAuditStore.record(context, b)
        assertTrue(ReadTimeReportAuditStore.markReported(context, listOf(a.reportKey()), now).isEmpty())
        assertEquals(0, ReadTimeReportAuditStore.snapshot(context, 11, now).today.reportedRecords)
        assertEquals(0, ReadTimeReportAuditStore.snapshot(context, 22, now).today.reportedRecords)

        val unique = readTimeEvent(11, now, book = 2)
        ReadTimeReportAuditStore.record(context, unique)
        assertEquals(setOf(11L), ReadTimeReportAuditStore.markReported(context, listOf(unique.reportKey(), unique.reportKey()), now))
        assertTrue(ReadTimeReportAuditStore.markReported(context, listOf(unique.reportKey()), now).isEmpty())
        assertEquals(1, ReadTimeReportAuditStore.snapshot(context, 11, now).today.reportedRecords)
        assertEquals(0, ReadTimeReportAuditStore.snapshot(context, 22, now).today.reportedRecords)
        ReadTimeReportAuditStore.record(context, unique.copy(recordedAt = now + 1, reportedAt = null))
        assertTrue(ReadTimeReportAuditStore.markReported(context, listOf(unique.reportKey()), now).isEmpty())
    }

    @Test fun cloudBaselineAndLimitPersistTogetherAndClearingOnlyAffectsTheSelectedAudit() {
        ReadTimeCloudAuditStore.reconcile(context, 11, ReadTimeCloudProbe.Result(0, 0), now)
        ReadTimeCloudAuditStore.reconcile(context, 22, ReadTimeCloudProbe.Result(200, 200), now)
        val a = readTimeEvent(11, now)
        val b = readTimeEvent(22, now, book = 2)
        listOf(a, b).forEach { ReadTimeReportAuditStore.record(context, it); store.record(it) }
        ReadTimeReportAuditStore.markReported(context, listOf(a.reportKey(), b.reportKey()), now)
        val verified = ReadTimeCloudAuditStore.reconcile(context, 11, ReadTimeCloudProbe.Result(80, 80), now + 1)
        assertTrue(verified!!.automaticLimitApplied)
        assertEquals(1.333, ReadTimeCloudAuditStore.automaticLimit(context, 11)!!, 0.00001)
        assertNull(ReadTimeCloudAuditStore.automaticLimit(context, 22))
        assertEquals(200, ReadTimeCloudAuditStore.snapshot(context, 22).baseline!!.todaySeconds)

        ConfigRuntime.clear()
        SettingsRuntime.initialize(context)
        SettingsRuntime.putReadTimeMultiplier(context, "2.5")
        SettingsRuntime.putBoolean(context, SettingIds.KEY_READ_TIME_MULTIPLIER_ENABLED, false)
        assertEquals(1.333, ReadTimeCloudAuditStore.automaticLimit(context, 11)!!, 0.00001)
        assertEquals(120_000L, ReadTimeCloudAuditStore.snapshot(context, 11).baseline!!.localReportedMillis)
        ReadTimeReportAuditStore.clear(context, 11)
        ReadTimeCloudAuditStore.clear(context, 11)
        assertNull(ReadTimeCloudAuditStore.snapshot(context, 11).baseline)
        assertEquals(1.333, ReadTimeCloudAuditStore.automaticLimit(context, 11)!!, 0.00001)
        assertEquals(60, store.totals(11, now).todaySeconds)
        assertEquals(1, ReadTimeReportAuditStore.snapshot(context, 22, now).today.reportedRecords)
    }

    @Test fun legacyGlobalCloudLimitNeverAppliesToAnAccount() {
        val legacy = JSONObject().put("version", 1).put("baseline", JSONObject().put("todaySeconds", 90))
        SettingsRuntime.putReadTimeCloudAuditState(context, legacy.toString())
        SettingsRuntime.putReadTimeEffectiveMultiplier(context, "1.2")
        SettingsRuntime.initialize(context)
        assertNull(ReadTimeCloudAuditStore.automaticLimit(context, 11))
        assertNull(ReadTimeCloudAuditStore.automaticLimit(context, 22))
        assertNull(ReadTimeCloudAuditStore.snapshot(context, 11).baseline)
        val retained = JSONObject(SettingsRuntime.readTimeCloudAuditState(context))
        assertEquals(90, retained.getJSONObject("unassigned").getJSONObject("baseline").getInt("todaySeconds"))
        assertEquals("1.2", retained.getString("unassignedEffectiveMultiplier"))
        assertEquals("", SettingsRuntime.readTimeEffectiveMultiplierRaw(context))
    }

    @Test fun manualOverrideClearsOnlyTheAccountLimitAndStartsANewComparisonInterval() {
        for (user in listOf(11L, 22L)) {
            ReadTimeCloudAuditStore.reconcile(context, user, ReadTimeCloudProbe.Result(0, 0), now)
            val event = readTimeEvent(user, now, book = user)
            ReadTimeReportAuditStore.record(context, event)
            ReadTimeReportAuditStore.markReported(context, listOf(event.reportKey()), now)
            ReadTimeCloudAuditStore.reconcile(context, user, ReadTimeCloudProbe.Result(80, 80), now + 1)
            assertEquals(1.333, ReadTimeCloudAuditStore.automaticLimit(context, user)!!, 0.00001)
        }
        val history = ReadTimeCloudAuditStore.snapshot(context, 11).latest
        val other = ReadTimeCloudAuditStore.snapshot(context, 22)
        assertTrue(ReadTimeCloudAuditStore.clearAutomaticLimit(context, 11))
        assertNull(ReadTimeCloudAuditStore.automaticLimit(context, 11))
        assertNull(ReadTimeCloudAuditStore.snapshot(context, 11).baseline)
        assertEquals(history, ReadTimeCloudAuditStore.snapshot(context, 11).latest)
        assertEquals(other, ReadTimeCloudAuditStore.snapshot(context, 22))
        assertEquals(1.333, ReadTimeCloudAuditStore.automaticLimit(context, 22)!!, 0.00001)
        assertEquals(1, ReadTimeReportAuditStore.snapshot(context, 11, now).today.reportedRecords)
        ReadTimeCloudAuditStore.reconcile(context, 11, ReadTimeCloudProbe.Result(80, 80), now + 2)
        assertNull(ReadTimeCloudAuditStore.automaticLimit(context, 11))
    }
}

internal fun readTimeEvent(userId: Long?, now: Long, book: Long = 1, original: Long = 60_000) = ReadTimeAuditEvent(
    recordedAt = now, originalMillis = original, requestedMillis = original * 2, persistedMillis = original * 2,
    multiplier = 2.0, adjustmentRequested = true, adjustmentApplied = true, clamped = false,
    daoSuccess = true, daoError = null, scaleError = null, restoreFailed = false, encodedAfterDao = true, daoLatencyMillis = 1,
    details = ReadTimeEntityDetails(
        dateStart = ReadTimeLocalStatisticsPolicy.dayKey(now), startTime = now, endTime = now + 60_000,
        bookId = book, bookName = null, bookType = 1, startChapterId = 1, endChapterId = 1,
        chapterVip = 0, scrollMode = 1, unlockStatus = 0, unlockReason = 0, source = null, extraJson = null, userId = userId,
    ),
)
