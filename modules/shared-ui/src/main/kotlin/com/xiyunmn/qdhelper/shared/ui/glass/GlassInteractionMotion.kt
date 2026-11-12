package com.xiyunmn.qdhelper.shared.ui.glass

import kotlin.math.abs

/** Press optics and signed drag deformation settle together after the selected slot arrives. */
internal class GlassInteractionMotion {
    private val press = GlassSpring(1f, 1000f, 0.001f)
    private val highlight = GlassSpring(0.5f, 300f, 0.001f)
    private val horizontal = GlassSpring(0.6f, 250f, 0.001f).apply { snapTo(1f) }
    private val vertical = GlassSpring(0.7f, 250f, 0.001f).apply { snapTo(1f) }
    private val velocity = GlassSpring(0.5f, 300f, 0.01f)
    private val offset = GlassSpring(1f, 300f, 0.5f)
    private var releasePending = false
    private var enabled = true
    private var dragFeedback = false
    private var dragStrength = 1f

    val progress: Float get() = press.value.coerceIn(0f, 1f)
    val highlightProgress: Float get() = highlight.value.coerceIn(0f, 1f)
    val scaleX: Float get() = (horizontal.value /
        (1f - (velocity.value * 0.075f).coerceIn(-0.2f, 0.2f)))
        .coerceIn(0.78f, GlassBarMotion.MAX_SCALE_X)
    val scaleY: Float get() = (vertical.value *
        (1f - (velocity.value * 0.025f).coerceIn(-0.2f, 0.2f)))
        .coerceIn(0.78f, GlassBarMotion.MAX_SCALE_Y)
    val lensSkew: Float get() = 0f
    val dragDistance: Float get() = offset.value
    val running: Boolean get() = releasePending || press.running || highlight.running ||
        horizontal.running || vertical.running || velocity.running || offset.running

    fun configure(enabled: Boolean, dragStrength: Float) {
        this.enabled = enabled
        this.dragStrength = dragStrength
        if (!this.enabled) reset()
    }

    fun press(interactive: Boolean = true, selection: Boolean = true) {
        if (!enabled) return
        releasePending = false
        dragFeedback = false
        press.animateTo(1f)
        highlight.animateTo(if (interactive) 1f else 0f)
        horizontal.animateTo(if (selection) 78f / 56f else 1f)
        vertical.animateTo(if (selection) 78f / 56f else 1f)
    }

    fun drag(distance: Float) {
        if (!enabled) return
        dragFeedback = true
        releasePending = false
        val raised = 1f + (78f / 56f - 1f) * dragStrength
        horizontal.animateTo(raised)
        vertical.animateTo(raised)
        press.animateTo(dragStrength.coerceAtMost(1f))
        highlight.animateTo(dragStrength.coerceAtMost(1f))
        offset.animateTo(distance * dragStrength.coerceAtMost(1f))
    }

    fun release() {
        if (!enabled) return
        releasePending = true
        highlight.animateTo(0f)
        offset.animateTo(0f)
    }

    fun cancel() {
        if (!enabled) return
        releasePending = false
        dragFeedback = false
        press.animateTo(0f)
        highlight.animateTo(0f)
        horizontal.animateTo(1f)
        vertical.animateTo(1f)
        velocity.animateTo(0f)
        offset.animateTo(0f)
    }

    fun advance(seconds: Float, normalizedVelocity: Float, arrived: Boolean) {
        if (!enabled) return
        if (releasePending && arrived) {
            releasePending = false
            lower()
        }
        val speed = if (press.target > 0f && normalizedVelocity.isFinite()) normalizedVelocity else 0f
        velocity.animateTo(speed * if (dragFeedback) dragStrength else 1f)
        press.advance(seconds)
        highlight.advance(seconds)
        horizontal.advance(seconds)
        vertical.advance(seconds)
        velocity.advance(seconds)
        offset.advance(seconds)
    }

    private fun lower() {
        press.animateTo(0f)
        horizontal.animateTo(1f)
        vertical.animateTo(1f)
    }

    fun reset() {
        releasePending = false
        dragFeedback = false
        press.snapTo(0f)
        highlight.snapTo(0f)
        horizontal.snapTo(1f)
        vertical.snapTo(1f)
        velocity.snapTo(0f)
        offset.snapTo(0f)
    }

    companion object {
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
