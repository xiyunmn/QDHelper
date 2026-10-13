package com.xiyunmn.qdhelper.feature.startup

import android.content.Context
import android.os.Looper
import com.xiyunmn.qdhelper.config.ConfigRuntime
import com.xiyunmn.qdhelper.config.SettingIds
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.hook.resource.RuntimeCleanupRegistry
import java.io.File
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
class StartupImageSynchronizerTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val first = StartupImageStore.SplashTarget("https://example.test/a.jpg", 1, ".jpg", StartupImageStore.SplashSource.CustomUrl, false)
    private val target = AtomicReference<StartupImageStore.SplashTarget?>(first)
    private val messages = CopyOnWriteArrayList<String>()
    private val synchronizers = mutableListOf<StartupImageSynchronizer>()

    @Before fun setUp() { ConfigRuntime.clear(); ConfigRuntime.initialize(context) }
    @After fun tearDown() { synchronizers.forEach(StartupImageSynchronizer::close); ConfigRuntime.clear() }

    @Test fun changesShareOneDebounceAndManualSyncConsumesThePendingTimer() {
        val synced = CopyOnWriteArrayList<Long>()
        val sync = synchronizer { _, image, _ -> synced += image.paperId; SyncResult(true, "完成") }
        sync.schedule(context)
        target.set(first.copy(paperId = 2, url = "https://example.test/b.jpg"))
        sync.schedule(context)
        sync.schedule(context)
        advance(899)
        assertTrue(synced.isEmpty())
        sync.schedule(context, immediate = true)
        pumpUntil { "完成" in messages }
        advance(1_000)
        assertEquals(listOf(2L), synced.toList())
        sync.close()
        sync.schedule(context, immediate = true)
        advance(1_000)
        assertEquals(listOf(2L), synced.toList())
    }

    @Test fun changingOrDisablingTheImageDuringSyncRetainsTheOldFile() {
        checkWriteLifetime(expectReplacement = false) { sync ->
            target.set(first.copy(paperId = 2, url = "https://example.test/b.jpg"))
            sync.schedule(context)
            target.set(null)
            sync.schedule(context)
        }
    }

    @Test fun logoOnlyChangeDoesNotInterruptImageReplacement() {
        checkWriteLifetime(expectReplacement = true) { sync ->
            target.set(first.copy(darkLogo = true))
            SettingsRuntime.putBoolean(context, SettingIds.KEY_CUSTOM_SPLASH_IMAGE_DARK_LOGO, true)
            sync.schedule(context)
        }
    }

    @Test fun hotReloadCancelsAnInFlightWriteAndSuppressesItsNotification() {
        checkWriteLifetime(expectReplacement = false) { RuntimeCleanupRegistry.runAndClear("startup-test") }
    }

    @Test fun hotReloadRemovesDelayedWorkBeforeAWorkerIsCreated() {
        val synced = CopyOnWriteArrayList<Long>()
        val sync = synchronizer { _, image, _ -> synced += image.paperId; SyncResult(true, "完成") }
        sync.schedule(context)
        RuntimeCleanupRegistry.runAndClear("startup-test")
        advance(1_000)
        assertTrue(synced.isEmpty())
        assertTrue(messages.isEmpty())
    }

    private fun checkWriteLifetime(expectReplacement: Boolean, change: (StartupImageSynchronizer) -> Unit) {
        val file = File(context.filesDir, "splash.jpg").apply { writeText("complete old image") }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val thread = AtomicReference<Thread>()
        val sync = synchronizer { _, _, isCurrent ->
            thread.set(Thread.currentThread())
            try {
                StartupImageStore.writeAtomically(file, isCurrent) { output ->
                    output.write("complete ".toByteArray())
                    entered.countDown()
                    while (release.count > 0) try { release.await() } catch (_: InterruptedException) { /* Slow source. */ }
                    output.write("new image".toByteArray())
                }
                SyncResult(true, "完成")
            } finally { finished.countDown() }
        }
        try {
            sync.schedule(context)
            advance(900)
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            change(sync)
            release.countDown()
            assertTrue(finished.await(3, TimeUnit.SECONDS))
            if (expectReplacement) pumpUntil { "完成" in messages }
            sync.close()
            thread.get().join(3_000)
            assertFalse(thread.get().isAlive)
            advance(1_000)
            assertEquals(if (expectReplacement) "complete new image" else "complete old image", file.readText())
            if (!expectReplacement) assertTrue(messages.isEmpty())
        } finally { release.countDown() }
    }

    private fun synchronizer(sync: (Context, StartupImageStore.SplashTarget, () -> Boolean) -> SyncResult): StartupImageSynchronizer =
        StartupImageSynchronizer({ target.get() }, sync) { _, message -> messages += message }.also(synchronizers::add)

    private fun advance(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))

    private fun pumpUntil(condition: () -> Boolean) {
        repeat(600) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        }
        fail("Startup image worker did not finish")
    }
}
