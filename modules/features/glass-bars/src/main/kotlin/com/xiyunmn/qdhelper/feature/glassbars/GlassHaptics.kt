package com.xiyunmn.qdhelper.feature.glassbars

import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View

/** Feedback belongs to the visible control, so Android retains its system and view opt-outs. */
internal class GlassHaptics(private val view: View) {
    var enabled: Boolean = true
    private var lastTickAt = -1L

    fun tick() {
        if (!enabled) return
        val now = SystemClock.uptimeMillis()
        if (lastTickAt >= 0 && now - lastTickAt < 40L) return
        val effect = if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK
            else HapticFeedbackConstants.CLOCK_TICK
        if (view.performHapticFeedback(effect)) lastTickAt = now
    }

    fun confirm(): Boolean = enabled && view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
}
