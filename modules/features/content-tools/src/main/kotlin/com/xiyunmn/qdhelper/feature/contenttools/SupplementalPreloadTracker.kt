package com.xiyunmn.qdhelper.feature.contenttools

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Keeps in-flight and completed supplemental profile requests as distinct states. */
internal class SupplementalPreloadTracker<K> {
    private enum class State { IN_FLIGHT, COMPLETED }
    private data class Entry(val attemptId: Long, val state: State)

    class Attempt<K> internal constructor(val key: K, internal val id: Long)

    private val attemptIds = AtomicLong()
    private val states = ConcurrentHashMap<K, Entry>()

    fun tryStart(key: K): Attempt<K>? {
        val attempt = Attempt(key, attemptIds.incrementAndGet())
        val entry = Entry(attempt.id, State.IN_FLIGHT)
        return attempt.takeIf { states.putIfAbsent(key, entry) == null }
    }

    fun finish(attempt: Attempt<K>, success: Boolean): Boolean {
        val inFlight = Entry(attempt.id, State.IN_FLIGHT)
        return if (success) {
            states.replace(attempt.key, inFlight, Entry(attempt.id, State.COMPLETED))
        } else {
            states.remove(attempt.key, inFlight)
        }
    }

    fun clear(key: K) {
        states.remove(key)
    }

    fun clearWhere(predicate: (K) -> Boolean) {
        states.keys.filter(predicate).forEach(states::remove)
    }

    fun clearAll() {
        states.clear()
    }
}
