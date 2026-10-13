package com.xiyunmn.qdhelper.hook.resource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RuntimeCleanupRegistryTest {
    @Test
    fun registrationDuringCleanupRunsImmediatelyOutsideRegistryLock() {
        val calls = java.util.concurrent.ConcurrentLinkedQueue<String>()
        RuntimeCleanupRegistry.register("parent") {
            val registering = Thread {
                RuntimeCleanupRegistry.register("late") { calls += "late" }
            }
            registering.start()
            registering.join(2_000)
            assertFalse(registering.isAlive)
        }
        val failures = mutableListOf<Throwable>()
        RuntimeCleanupRegistry.runAndClear { _, failure -> failures += failure }
        assertEquals(emptyList<Throwable>(), failures)
        assertEquals(listOf("late"), calls.toList())
        assertEquals(0, RuntimeCleanupRegistry.runAndClear { _, _ -> }.total)
    }

    @Test
    fun reusedCallbackKeepsItsReplacementHandleAndCleanupFailureIsolation() {
        val calls = mutableListOf<String>()
        RuntimeCleanupRegistry.register("same") { calls += "old" }
        val action: () -> Unit = { calls += "new" }
        val old = RuntimeCleanupRegistry.register("same", action)
        RuntimeCleanupRegistry.register("same", action)
        old.close()
        RuntimeCleanupRegistry.register("failure") { error("expected") }

        val failures = mutableListOf<String>()
        val report = RuntimeCleanupRegistry.runAndClear { key, _ -> failures += key }

        assertEquals(listOf("new"), calls)
        assertEquals(listOf("failure"), failures)
        assertEquals(1, report.completed)
        assertEquals(2, report.total)
        assertEquals(0, RuntimeCleanupRegistry.runAndClear { _, _ -> }.total)
    }
}
