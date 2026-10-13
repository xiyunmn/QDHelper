package com.xiyunmn.qdhelper.shared.fonts

import java.util.concurrent.CopyOnWriteArraySet

object FontLibraryChanges {
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun addListener(listener: () -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    fun notifyChanged() {
        listeners.forEach { listener -> runCatching { listener() } }
    }
}
