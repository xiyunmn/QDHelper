package com.xiyunmn.qdhelper.shared.ui.glass

import kotlin.math.abs

/** KernelSU press, signed drag deformation and delayed release, independent of Android's clock. */
internal class GlassInteractionMotion {
    private val press = GlassSpring(1f, 1000f, 0.001f)
    private val highlight = GlassSpring(0.5f, 300f, 0.001f)
    private val horizontal = GlassSpring(0.6f, 250f, 0.001f).apply { snapTo(1f) }
    private val vertical = GlassSpring(0.7f, 250f, 0.001f).apply { snapTo(1f) }
    private val velocity = GlassSpring(0.5f, 300f, 0.01f)
    private val offset = GlassSpring(1f, 300f, 0.5f)
    private var releasePending = false
    private var enabled = true

    val progress: Float get() = press.value.coerceIn(0f, 1f)
    val highlightProgress: Float get() = highlight.value.coerceIn(0f, 1f)
    val scaleX: Float get() = horizontal.value / (1f - (velocity.value * 0.075f).coerceIn(-0.2f, 0.2f))
    val scaleY: Float get() = vertical.value * (1f - (velocity.value * 0.025f).coerceIn(-0.2f, 0.2f))
    val dragDistance: Float get() = offset.value
    val running: Boolean get() = releasePending || press.running || highlight.running || horizontal.running || vertical.running || velocity.running || offset.running

    fun configure(enabled: Boolean) {
        this.enabled = enabled
        if (!enabled) reset()
    }

    fun press(interactive: Boolean = true) {
        if (!enabled) return
        releasePending = false
        press.animateTo(1f)
        highlight.animateTo(if (interactive) 1f else 0f)
        horizontal.animateTo(78f / 56f)
        vertical.animateTo(78f / 56f)
    }

    fun drag(distance: Float) { if (enabled) offset.animateTo(distance) }

    fun release() {
        if (!enabled) return
        releasePending = true
        // Finger light fades on lift; the raised lens waits for its selected tab to arrive.
        highlight.animateTo(0f)
        offset.animateTo(0f)
    }

    fun advance(seconds: Float, normalizedVelocity: Float, arrived: Boolean) {
        if (!enabled) return
        if (releasePending && arrived) {
            releasePending = false
            press.animateTo(0f)
            horizontal.animateTo(1f)
            vertical.animateTo(1f)
        }
        velocity.animateTo(if (press.target > 0f && normalizedVelocity.isFinite()) normalizedVelocity else 0f)
        press.advance(seconds)
        highlight.advance(seconds)
        horizontal.advance(seconds)
        vertical.advance(seconds)
        velocity.advance(seconds)
        offset.advance(seconds)
    }

    fun reset() {
        releasePending = false
        press.snapTo(0f)
        highlight.snapTo(0f)
        horizontal.snapTo(1f)
        vertical.snapTo(1f)
        velocity.snapTo(0f)
        offset.snapTo(0f)
    }

    companion object {
        /** KernelSU normalizes velocity by the first-to-last tab travel, not the whole panel. */
        fun normalizedVelocity(pixelsPerSecond: Float, travelPixels: Float): Float =
            if (pixelsPerSecond.isFinite() && travelPixels.isFinite() && travelPixels > 0.5f) {
                (pixelsPerSecond / travelPixels).coerceIn(-20f, 20f)
            } else 0f

        fun rubberBand(distance: Float, width: Float, density: Float): Float {
            if (width <= 0f || !distance.isFinite() || distance == 0f) return 0f
            val fraction = (abs(distance) / width).coerceIn(0f, 1f)
            var low = 0f
            var high = 1f
            repeat(14) {
                val t = (low + high) * 0.5f
                if (1.74f * t * t - 0.74f * t * t * t < fraction) low = t else high = t
            }
            val t = (low + high) * 0.5f
            return 4f * density * t * t * (3f - 2f * t) * if (distance < 0f) -1f else 1f
        }
    }
}
