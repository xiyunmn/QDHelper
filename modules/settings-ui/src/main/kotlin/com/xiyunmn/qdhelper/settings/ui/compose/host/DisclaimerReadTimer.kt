package com.xiyunmn.qdhelper.settings.ui.compose.host

/** Counts only observed foreground reading time; background time never completes the gate. */
internal class DisclaimerReadTimer {
    private var readMillis = 0L
    private var previous: Long? = null
    private var wasVisible = false

    fun update(now: Long, visible: Boolean): Int {
        previous?.let { before ->
            if (visible && wasVisible) readMillis += (now - before).coerceIn(0, 250)
        }
        previous = now
        wasVisible = visible
        return ((10_000L - readMillis).coerceAtLeast(0) + 999).div(1000).toInt()
    }
}
