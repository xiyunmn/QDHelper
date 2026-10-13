package com.xiyunmn.qdhelper.host.resolver.dexkit

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class DexKitEngineLifecycleTest {
    @Test
    fun closeInvalidatesOldBatchWithoutWaitingForItsWorkAndAllowsANewSession() {
        val context = RuntimeEnvironment.getApplication()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val deliveries = AtomicInteger()
        try {
            val old = DexKitEngine.submitBatch(context, context.classLoader) { token, _ ->
                entered.countDown()
                while (release.count > 0) {
                    try { release.await() } catch (_: InterruptedException) { /* Native work may ignore cancellation. */ }
                }
                if (DexKitEngine.isCurrent(token)) deliveries.incrementAndGet()
                finished.countDown()
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val oldGeneration = DexKitEngine.generation
            DexKitEngine.close()
            assertTrue(old.isCancelled)
            assertFalse(DexKitEngine.isCurrent(oldGeneration))
            DexKitEngine.submitBatch(context, context.classLoader) { token, _ ->
                assertTrue(DexKitEngine.isCurrent(token))
                deliveries.incrementAndGet()
            }.get(2, TimeUnit.SECONDS)
            release.countDown()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertEquals(1, deliveries.get())
        } finally {
            release.countDown()
            DexKitEngine.close()
        }
    }
}
