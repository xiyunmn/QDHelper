package com.xiyunmn.qdhelper.hook.concurrency

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class ManagedExecutorTest {
    @Test
    fun closeCancelsWaitersButReleasesResourcesOnlyAfterRunningWorkExits() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val terminated = CountDownLatch(1)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val terminations = AtomicInteger()
        val worker = ManagedExecutor("test.native", "test-native", onFailure = failures::add, onTerminated = {
            terminations.incrementAndGet()
            terminated.countDown()
        })
        try {
            val running = worker.submit {
                entered.countDown()
                while (release.count > 0) {
                    try { release.await() } catch (_: InterruptedException) { /* Simulate native work. */ }
                }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val queued = worker.submit { error("Cancelled work ran") }
            worker.close()
            assertTrue(running.isCancelled)
            assertTrue(queued.isCancelled)
            assertFalse(worker.execute { error("Closed worker reopened") })
            assertTrue(worker.submit { 1 }.isCancelled)
            assertEquals(1L, terminated.count)
            release.countDown()
            assertTrue(terminated.await(2, TimeUnit.SECONDS))
            worker.close()
            assertEquals(1, terminations.get())
            assertTrue(failures.isEmpty())
        } finally {
            release.countDown()
            worker.close()
        }
    }

    @Test
    fun submissionsRacingCloseAllCompleteWithoutRejectionEscaping() {
        val failures = ConcurrentLinkedQueue<Throwable>()
        val results = ConcurrentLinkedQueue<Future<Int>>()
        val worker = ManagedExecutor("test.race", "test-race", onFailure = failures::add)
        val start = CountDownLatch(1)
        val submitter = Thread {
            start.await()
            repeat(200) { results += worker.submit { it } }
        }
        submitter.start()
        start.countDown()
        worker.close()
        submitter.join(2_000)
        assertFalse(submitter.isAlive)
        assertEquals(200, results.size)
        assertTrue(results.all { it.isDone })
        assertTrue(failures.isEmpty())
    }
}
