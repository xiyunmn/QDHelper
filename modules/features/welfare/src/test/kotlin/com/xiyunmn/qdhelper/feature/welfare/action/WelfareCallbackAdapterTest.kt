package com.xiyunmn.qdhelper.feature.welfare.action

import org.junit.Assert.*
import org.junit.Test

class WelfareCallbackAdapterTest {
    fun interface Consumer { fun accept(value: Any?) }

    @Test fun preservesResponseIdentityAndInvokesHostExactlyOnce() {
        val events = mutableListOf<String>()
        val response = Any()
        val delegate = Consumer { assertSame(response, it); events += "host" }
        val wrapped = welfareConsumerProxy(Consumer::class.java, delegate) { value, proceed ->
            assertSame(response, value)
            events += "before"
            proceed()
            events += "after"
        } as Consumer
        wrapped.accept(response)
        assertEquals(listOf("before", "host", "after"), events)
    }

    @Test fun preservesOriginalErrorObjectAndHostException() {
        val networkError = IllegalStateException("test network error")
        val hostError = IllegalArgumentException("test consumer error")
        var finalized = false
        val delegate = Consumer { assertSame(networkError, it); throw hostError }
        val wrapped = welfareConsumerProxy(Consumer::class.java, delegate) { _, proceed ->
            try { proceed() } finally { finalized = true }
        } as Consumer
        val caught = runCatching { wrapped.accept(networkError) }.exceptionOrNull()
        assertSame(hostError, caught)
        assertTrue(finalized)
    }

    @Test fun staleTerminalCanBeDroppedWithoutCallingHostAgain() {
        var calls = 0
        var terminal = false
        val wrapped = welfareConsumerProxy(Consumer::class.java, Consumer { calls++ }) { _, proceed ->
            if (!terminal) { terminal = true; proceed() }
        } as Consumer
        wrapped.accept(Any())
        wrapped.accept(IllegalStateException())
        assertEquals(1, calls)
        assertEquals(wrapped, wrapped)
        assertFalse(wrapped.equals(Any()))
    }
}
