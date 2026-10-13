package com.xiyunmn.qdhelper.shared.ui.glass

import android.graphics.RectF
import android.view.MotionEvent
import kotlin.math.abs

/** One clock and one rendered center for both Compose layers and native click targeting. */
class GlassBarMotion(private val density: Float) {
    private val selection = GlassSelectionMotion()
    private val interaction = GlassInteractionMotion()
    private val hostSelection = RectF()
    val selectionRect = RectF()
    var onChanged: (() -> Unit)? = null
    var onInteractionOffset: ((Float) -> Unit)? = null
    private var width = 0
    private var height = 0
    private var active = true
    private var animate = true
    private var pressEnabled = true
    private var dragging = false
    private var pressed = false
    private var dragOffset = 0f
    private var startX = 0f
    private var travelStart = 0f
    private var travelEnd = 0f
    private var previousX = 0f
    private var previousTime = 0L
    private var fingerVelocity = 0f
    private var sampleAge = 0f
    private var lastOffset = Float.NaN
    var touchX = 0f
        private set
    var touchY = 0f
        private set
    val selectionCenterX: Float? get() = if (selection.visible) selection.center * width else null
    val selectionVisible: Boolean get() = selection.visible
    val progress: Float get() = interaction.progress
    val highlight: Float get() = interaction.highlightProgress
    val scaleX: Float get() = interaction.scaleX
    val scaleY: Float get() = interaction.scaleY
    val running: Boolean get() = active && (selection.running || interaction.running)

    fun configure(width: Int, height: Int, pressEnabled: Boolean, animate: Boolean) {
        if (this.width != width || this.height != height) reset()
        this.width = width
        this.height = height
        this.animate = animate
        this.pressEnabled = pressEnabled
        interaction.configure(pressEnabled && animate)
        if (!animate) selection.settle()
        changed()
    }

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        if (!value) reset()
    }

    fun setSelectionTravelRange(first: Float, last: Float) {
        travelStart = first.takeIf(Float::isFinite) ?: 0f
        travelEnd = last.takeIf { it.isFinite() && it >= travelStart } ?: travelStart
    }

    fun setSelection(left: Float, top: Float, right: Float, bottom: Float) {
        if (width <= 0 || height <= 0) return
        hostSelection.set(left, top, right, bottom)
        if (dragging) return
        val hadSelection = selection.visible
        val previous = selection.targetCenter
        target(left, top, right, bottom, animate && active)
        if (hadSelection && abs(selection.targetCenter - previous) > 0.0001f && !pressed && active && animate) {
            interaction.press(interactive = false)
            interaction.release()
            changed()
        }
    }

    private fun target(left: Float, top: Float, right: Float, bottom: Float, animated: Boolean) {
        if (selection.target(left / width, top / height, right / width, bottom / height, animated)) changed()
    }

    fun clearSelection() {
        if (!selection.visible && hostSelection.isEmpty && !dragging && !pressed &&
            !selection.running && !interaction.running && progress == 0f && highlight == 0f &&
            scaleX == 1f && scaleY == 1f && interaction.dragDistance == 0f) return
        pressed = false
        dragging = false
        hostSelection.setEmpty()
        selection.clear()
        interaction.reset()
        changed()
    }

    fun beginSelectionDrag(x: Float, eventTime: Long): Boolean {
        if (!active || !selection.visible || width <= 0) return false
        // Pick up the currently displayed capsule, including an interrupted settling animation.
        selection.target(selection.left, selection.top, selection.right, selection.bottom, false)
        startX = x
        dragOffset = x - selection.center * width
        previousX = x
        previousTime = eventTime
        fingerVelocity = 0f
        sampleAge = 0f
        dragging = true
        return true
    }

    fun moveSelectionDrag(x: Float, eventTime: Long) {
        if (!dragging || hostSelection.isEmpty) return
        val half = hostSelection.width() / 2f
        val minimum = if (travelEnd > travelStart) travelStart else half
        val maximum = if (travelEnd > travelStart) travelEnd else width - half
        val center = (x - dragOffset).coerceIn(minimum, maximum.coerceAtLeast(minimum))
        val elapsed = eventTime - previousTime
        if (elapsed > 0) {
            fingerVelocity = if (elapsed <= 100) (x - previousX) * 1000f / elapsed else 0f
            previousX = x
            previousTime = eventTime
            sampleAge = 0f
        }
        // Never put a spring between the finger and the displayed/committed center.
        target(center - half, hostSelection.top, center + half, hostSelection.bottom, false)
        interaction.drag(x - startX)
        changed()
    }

    fun cancelSelectionDrag() {
        if (!dragging) return
        dragging = false
        fingerVelocity = 0f
        if (!hostSelection.isEmpty) target(hostSelection.left, hostSelection.top, hostSelection.right, hostSelection.bottom, active && animate)
    }

    fun observeTouch(action: Int, x: Float, y: Float) {
        if (!active) return
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                if (x !in 0f..width.toFloat() || y !in 0f..height.toFloat()) return
                pressed = true
                touchX = x
                touchY = y
                interaction.configure(pressEnabled && animate)
                interaction.press()
            }
            MotionEvent.ACTION_MOVE -> if (pressed) {
                touchX = x.coerceIn(0f, width.toFloat())
                touchY = y.coerceIn(0f, height.toFloat())
            }
            MotionEvent.ACTION_UP -> {
                cancelSelectionDrag()
                if (pressed) { pressed = false; interaction.release() }
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> reset()
        }
        changed()
    }

    fun advance(seconds: Float) {
        if (!active) return
        selection.advance(seconds)
        sampleAge += seconds
        val velocity = if (dragging) {
            if (sampleAge <= 0.05f) fingerVelocity else 0f
        } else selection.velocity * width
        interaction.advance(seconds, GlassInteractionMotion.normalizedVelocity(velocity, travelEnd - travelStart),
            abs(selection.center - selection.targetCenter) < 0.025f)
        changed()
    }

    fun reset() {
        pressed = false
        cancelSelectionDrag()
        selection.settle()
        interaction.reset()
        changed()
    }

    private fun changed() {
        selectionRect.set(selection.left * width, selection.top * height, selection.right * width, selection.bottom * height)
        val offset = if (selection.visible) GlassInteractionMotion.rubberBand(interaction.dragDistance, width.toFloat(), density) else 0f
        if (lastOffset != offset) { lastOffset = offset; onInteractionOffset?.invoke(offset) }
        onChanged?.invoke()
    }

    fun close() {
        reset()
        onChanged = null
        onInteractionOffset = null
    }
}
