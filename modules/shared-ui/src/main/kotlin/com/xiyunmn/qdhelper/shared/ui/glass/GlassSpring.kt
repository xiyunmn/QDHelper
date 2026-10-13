package com.xiyunmn.qdhelper.shared.ui.glass

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Adapted from liuran001/WeChat-LiquidGlass Spring.java, commit
 * 5d120f15e08665d27f1a552f6138101aa6dee4b2 (MIT, Copyright (c) 2026 liuran001).
 * See resources/licenses/wechat-liquidglass.txt. Substeps keep dropped frames stable.
 */
internal class GlassSpring(
    private val dampingRatio: Float,
    private val stiffness: Float,
    private val threshold: Float = 0.0005f,
) {
    var value = 0f
        private set
    var velocity = 0f
        private set
    var target = 0f
        private set
    var running = false
        private set

    fun animateTo(value: Float) {
        if (target == value) return
        target = value
        running = true
    }

    fun snapTo(value: Float) {
        this.value = value
        target = value
        velocity = 0f
        running = false
    }

    fun advance(seconds: Float) {
        if (!running) return
        var remaining = seconds.coerceIn(0f, 0.064f)
        val damping = 2f * dampingRatio * sqrt(stiffness)
        while (remaining > 0f) {
            val step = min(remaining, 1f / 240f)
            remaining -= step
            velocity += (-stiffness * (value - target) - damping * velocity) * step
            value += velocity * step
        }
        if (abs(value - target) < threshold && abs(velocity) < threshold * 10f) snapTo(target)
    }
}
