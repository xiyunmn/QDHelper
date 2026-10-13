package com.xiyunmn.qdhelper.feature.glassbars

import java.lang.ref.WeakReference

/** Small identity-scoped registry. Neither renderer nor view may keep an Activity alive. */
internal class FlutterViewportOverrides {
    private class Entry(renderer: Any, view: Any, var bottom: Int) {
        val renderer = WeakReference(renderer)
        val view = WeakReference(view)
    }

    private val entries = mutableListOf<Entry>()

    fun put(renderer: Any, view: Any, bottom: Int): Boolean {
        prune()
        val existing = entries.firstOrNull { it.renderer.get() === renderer && it.view.get() === view }
        if (existing?.bottom == bottom) return false
        entries.removeAll { it.renderer.get() === renderer || it.view.get() === view }
        entries += Entry(renderer, view, bottom)
        return true
    }

    fun removeView(view: Any) {
        entries.removeAll { it.view.get() == null || it.renderer.get() == null || it.view.get() === view }
    }

    fun bottomFor(renderer: Any): Int? = entries.firstOrNull {
        it.renderer.get() === renderer && it.view.get() != null
    }?.bottom

    fun clear() = entries.clear()

    private fun prune() {
        entries.removeAll { it.view.get() == null || it.renderer.get() == null }
    }
}

/** Metrics are reused by FlutterView; the override must not escape the synchronous JNI call. */
internal inline fun <T> withFlutterBottomPadding(
    bottom: Int?,
    read: () -> Int,
    write: (Int) -> Boolean,
    proceed: () -> T,
): T {
    if (bottom == null) return proceed()
    val original = read()
    val desired = GlassGeometry.flutterPadding(original, bottom)
    if (desired == original || !write(desired)) return proceed()
    return try {
        proceed()
    } finally {
        write(original)
    }
}
