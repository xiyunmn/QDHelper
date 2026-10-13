package com.xiyunmn.qdhelper.hook.resource

import com.xiyunmn.qdhelper.foundation.logging.RuntimeLog

object RuntimeCleanupRegistry {
    private val lock = Any()
    private val actions = linkedMapOf<String, () -> Unit>()
    private var clearing: ((String, Throwable) -> Unit)? = null

    fun register(key: String, action: () -> Unit): AutoCloseable {
        // Each handle owns a distinct registration, even when the same callback is registered twice.
        val registered = { action() }
        val activeCleanup = synchronized(lock) {
            clearing.also { if (it == null) actions[key] = registered }
        }
        if (activeCleanup != null) {
            runCatching(action).onFailure { runCatching { activeCleanup(key, it) } }
            return AutoCloseable {}
        }
        return AutoCloseable { synchronized(lock) { actions.remove(key, registered) } }
    }

    fun runAndClear(onFailure: (String, Throwable) -> Unit): CleanupReport {
        val snapshot = synchronized(lock) {
            if (clearing != null) return CleanupReport(0, 0)
            clearing = onFailure
            actions.toList().also { actions.clear() }
        }
        var completed = 0
        try {
            snapshot.forEach { (key, action) ->
                runCatching(action).onSuccess { completed += 1 }.onFailure { runCatching { onFailure(key, it) } }
            }
        } finally {
            synchronized(lock) { clearing = null }
        }
        return CleanupReport(completed = completed, total = snapshot.size)
    }

    fun runAndClear(reason: String): CleanupReport {
        val report = runAndClear { key, throwable ->
            RuntimeLog.w("Hot reload cleanup failed: key=$key, reason=$reason, error=${throwable.javaClass.name}")
        }
        RuntimeLog.i("Hot reload cleanup finished: reason=$reason, completed=${report.completed}/${report.total}")
        return report
    }

    data class CleanupReport(val completed: Int, val total: Int)
}
