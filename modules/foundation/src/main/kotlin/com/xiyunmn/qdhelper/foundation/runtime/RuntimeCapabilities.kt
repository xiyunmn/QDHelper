package com.xiyunmn.qdhelper.foundation.runtime

/** Process-local observations, separate from configured switches and DexKit matches. */
object RuntimeCapabilities {
    data class State(val id: String, val title: String, val available: Boolean, val detail: String)
    private val states = linkedMapOf<String, State>()

    @Synchronized fun record(id: String, title: String, available: Boolean, detail: String): Boolean {
        val state = State(id, title, available, detail.take(600))
        if (states[id] == state) return false
        if (states.size >= 128 && id !in states) states.remove(states.keys.first())
        states[id] = state
        return true
    }
    @Synchronized fun get(id: String): State? = states[id]
    @Synchronized fun snapshot(): List<State> = states.values.toList()
    @Synchronized fun removePrefix(prefix: String) { states.keys.removeAll { it.startsWith(prefix) } }
}
