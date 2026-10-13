package com.xiyunmn.qdhelper.feature.readtime

import android.content.Context
import android.os.Looper
import com.xiyunmn.qdhelper.config.ConfigRuntime
import com.xiyunmn.qdhelper.config.SettingIds
import com.xiyunmn.qdhelper.config.SettingsRuntime
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class ReadTimeSessionTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val account = AtomicReference<Long?>(11L)
    private val now = AtomicLong(System.currentTimeMillis())
    private val sessions = mutableListOf<ReadTimeSession>()

    @Before fun setUp() {
        ConfigRuntime.clear()
        ConfigRuntime.initialize(context)
        SettingsRuntime.putBoolean(context, SettingIds.KEY_MODULE_ENABLED, true)
        SettingsRuntime.putBoolean(context, SettingIds.KEY_READ_TIME_MULTIPLIER_ENABLED, true)
        SettingsRuntime.putReadTimeMultiplier(context, "2.0")
    }

    @After fun tearDown() {
        sessions.forEach(ReadTimeSession::close)
        ConfigRuntime.clear()
    }

    @Test fun manualQueryReplacesTheTimerAndAutomaticQueriesRespectTheAccountInterval() {
        val calls = CopyOnWriteArrayList<Long?>()
        val session = session { calls += account.get(); ReadTimeCloudProbe.Result(10, 10) }
        session.scheduleAutomatic(setOf(11L))
        advance(14_999)
        assertTrue(calls.isEmpty())
        val results = CopyOnWriteArrayList<ReadTimeSession.QueryResult>()
        session.query(results::add)
        pumpUntil { results.size == 1 }
        advance(15_000)
        assertEquals(listOf(11L), calls.toList())
        session.scheduleAutomatic(setOf(11L))
        advance(15_000)
        assertEquals(1, calls.size)

        account.set(22L)
        session.scheduleAutomatic(setOf(22L))
        advance(15_000)
        pumpUntil { ReadTimeCloudAuditStore.snapshot(context, 22).baseline != null }
        assertEquals(listOf(11L, 22L), calls.toList())
        account.set(11L)
        session.scheduleAutomatic(setOf(11L))
        advance(15_000)
        assertEquals(2, calls.size)
        advance(120_000)
        session.scheduleAutomatic(setOf(11L))
        advance(15_000)
        pumpUntil { calls.size == 3 }
        assertEquals(11L, calls.last())
    }

    @Test fun accountDayAndUnknownIdentityPreventStaleDelayedRequestsFromStarting() {
        val calls = AtomicInteger()
        val session = session { calls.incrementAndGet(); ReadTimeCloudProbe.Result(10, 10) }
        session.scheduleAutomatic(setOf(11L))
        account.set(22L)
        advance(15_000)
        assertEquals(0, calls.get())
        session.scheduleAutomatic(setOf(22L))
        now.addAndGet(86_400_000)
        advance(15_000)
        assertEquals(0, calls.get())
        for (unknown in listOf(null, 0L)) {
            account.set(unknown)
            val results = mutableListOf<ReadTimeSession.QueryResult>()
            session.query(results::add)
            assertEquals(unknown, results.single().userId)
            assertNull(results.single().cloud)
        }
        assertEquals(0, calls.get())
    }

    @Test fun changedSettingsInvalidateInFlightResultsEvenWhenTheValueIsRestored() {
        checkLateResult { _, _ ->
            SettingsRuntime.putBoolean(context, SettingIds.KEY_READ_TIME_MULTIPLIER_ENABLED, false)
            SettingsRuntime.putBoolean(context, SettingIds.KEY_READ_TIME_MULTIPLIER_ENABLED, true)
            SettingsRuntime.putReadTimeMultiplier(context, "2.5")
            SettingsRuntime.putReadTimeMultiplier(context, "2.0")
        }
    }

    @Test fun clearingAuditInvalidatesTheResultAndReleasesTheManualWaiter() {
        checkLateResult { session, _ -> session.clearAudit(11) }
    }

    @Test fun explicitMultiplierOverrideInvalidatesCloudResultsEvenIfTheConfiguredValueIsUnchanged() {
        checkLateResult { session, _ -> assertTrue(session.overrideAutomaticLimit()) }
    }

    @Test fun dismissingTheDialogPreventsResultDeliveryAndBaselineWriteback() {
        checkLateResult(expectCancellation = false) { _, handle -> handle.close() }
    }

    @Test fun changingAccountsInvalidatesTheResultBeforeReturningToTheOriginalAccount() {
        checkLateResult { session, _ ->
            account.set(22L)
            session.scheduleAutomatic(setOf(22L))
            account.set(11L)
        }
    }

    @Test fun failedFetchReleasesTheWaiterAndTheSerialWorkerAcceptsTheNextRequest() {
        val calls = AtomicInteger()
        val session = session {
            if (calls.incrementAndGet() == 1) error("Network unavailable")
            ReadTimeCloudProbe.Result(10, 10)
        }
        val results = CopyOnWriteArrayList<ReadTimeSession.QueryResult>()
        session.query(results::add)
        pumpUntil { results.size == 1 }
        assertNull(results.single().cloud)
        session.query(results::add)
        pumpUntil { results.size == 2 }
        assertEquals(10, results.last().cloud!!.todaySeconds)
    }

    @Test fun closingStopsTimersDiscardsLateResultsAndClosesTheDatabase() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val thread = AtomicReference<Thread>()
        val results = CopyOnWriteArrayList<ReadTimeSession.QueryResult>()
        val session = session {
            thread.set(Thread.currentThread())
            entered.countDown()
            awaitIgnoringInterruption(release)
            ReadTimeCloudProbe.Result(10, 10)
        }
        try {
            session.query(results::add)
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            session.close()
            release.countDown()
            thread.get().join(3_000)
            assertFalse(thread.get().isAlive)
            advance(20_000)
            assertTrue(results.isEmpty())
            assertNull(ReadTimeCloudAuditStore.snapshot(context, 11).baseline)
            assertThrows(IllegalStateException::class.java) { session.statistics.totals(11) }
        } finally { release.countDown() }

        val calls = AtomicInteger()
        val delayed = session { calls.incrementAndGet(); null }
        delayed.scheduleAutomatic(setOf(11L))
        delayed.close()
        advance(20_000)
        assertEquals(0, calls.get())
    }

    private fun checkLateResult(expectCancellation: Boolean = true, invalidate: (ReadTimeSession, AutoCloseable) -> Unit) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val baselineBeforeNextRequest = AtomicReference<ReadTimeCloudBaseline>()
        val oldResults = CopyOnWriteArrayList<ReadTimeSession.QueryResult>()
        val newResults = CopyOnWriteArrayList<ReadTimeSession.QueryResult>()
        val session = session {
            if (calls.incrementAndGet() == 1) {
                entered.countDown()
                awaitIgnoringInterruption(release)
            } else {
                baselineBeforeNextRequest.set(ReadTimeCloudAuditStore.snapshot(context, 11).baseline)
            }
            ReadTimeCloudProbe.Result(calls.get() * 10, calls.get() * 10)
        }
        try {
            val handle = session.query(oldResults::add)
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            invalidate(session, handle)
            session.query(newResults::add)
            release.countDown()
            pumpUntil { newResults.size == 1 }
            assertNull(baselineBeforeNextRequest.get())
            if (expectCancellation) assertTrue(oldResults.single().cancelled) else assertTrue(oldResults.isEmpty())
            assertEquals(20, ReadTimeCloudAuditStore.snapshot(context, 11).baseline!!.todaySeconds)
        } finally { release.countDown() }
    }

    private fun session(fetch: () -> ReadTimeCloudProbe.Result?): ReadTimeSession =
        ReadTimeSession(context, context.classLoader, account::get, fetch, now::get).also(sessions::add)

    private fun advance(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))

    private fun pumpUntil(condition: () -> Boolean) {
        repeat(600) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        }
        fail("Read-time worker did not finish")
    }

    private fun awaitIgnoringInterruption(latch: CountDownLatch) {
        while (latch.count != 0L) try { latch.await() } catch (_: InterruptedException) { /* Host request may finish late. */ }
    }
}
