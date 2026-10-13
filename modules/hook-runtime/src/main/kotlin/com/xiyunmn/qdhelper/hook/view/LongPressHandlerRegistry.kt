package com.xiyunmn.qdhelper.hook.view

import java.lang.ref.WeakReference
import java.util.WeakHashMap

class LongPressHandlerRegistry(
    private val onFailure: (LongPressRegistration, Throwable) -> Unit = { _, _ -> },
) {
    private val lock = Any()
    private val targets = WeakHashMap<Any, MutableList<Entry>>()

    fun register(
        target: Any,
        registration: LongPressRegistration,
        handler: (target: Any, proceed: () -> Boolean) -> LongPressResult,
    ): LongPressRegistrationResult = synchronized(lock) {
        val entries = targets.getOrPut(target) { mutableListOf() }
        if (entries.any { it.registration.owner == registration.owner && it.registration.id == registration.id }) {
            return LongPressRegistrationResult.DuplicateOwner
        }
        val entry = Entry(registration, handler)
        entries += entry
        entries.sortByDescending { it.registration.priority }
        val targetRef = WeakReference(target)
        LongPressRegistrationResult.Registered(
            AutoCloseable { targetRef.get()?.let { unregister(it, entry) } },
        )
    }

    fun dispatch(target: Any, proceed: () -> Boolean): LongPressResult {
        val entries = synchronized(lock) { targets[target]?.toList().orEmpty() }
        entries.forEach { entry ->
            val result = runCatching { entry.handler(target, proceed) }.getOrElse { throwable ->
                onFailure(entry.registration, throwable)
                LongPressResult.Pass
            }
            if (result is LongPressResult.Return) return result
        }
        return LongPressResult.Pass
    }

    fun hasRegistrations(target: Any): Boolean = synchronized(lock) { targets[target]?.isNotEmpty() == true }

    fun clear() = synchronized(lock) { targets.clear() }

    private fun unregister(target: Any, entry: Entry) = synchronized(lock) {
        val entries = targets[target] ?: return@synchronized
        entries.remove(entry)
        if (entries.isEmpty()) targets.remove(target)
    }

    private data class Entry(
        val registration: LongPressRegistration,
        val handler: (Any, () -> Boolean) -> LongPressResult,
    )
}
