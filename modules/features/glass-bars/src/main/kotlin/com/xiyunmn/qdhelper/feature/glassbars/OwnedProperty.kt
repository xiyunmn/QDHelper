package com.xiyunmn.qdhelper.feature.glassbars

/** Rebase on host writes. Restore only a value we still own, never an obsolete snapshot. */
internal class OwnedProperty<T>(private val read: () -> T, private val write: (T) -> Unit) : AutoCloseable {
    private data class State<T>(val original: T, val written: T)
    private var state: State<T>? = null

    fun update(transform: (T) -> T) {
        val current = read()
        val previous = state
        val original = if (previous != null && previous.written == current) previous.original else current
        val desired = transform(original)
        if (desired != current) write(desired)
        state = State(original, desired)
    }

    fun set(value: T) = update { value }

    override fun close() {
        val previous = state ?: return
        state = null
        if (read() == previous.written && previous.original != previous.written) write(previous.original)
    }
}
