package com.xiyunmn.qdhelper.shared.ui.glass

import android.graphics.RectF
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt
import java.util.ArrayDeque

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
    private var dragging = false
    private var settlingDrag = false
    private var clickSpeed = 0.6f
    private var pressed = false
    private var dragOffset = 0f
    private var startX = 0f
    private var travelStart = 0f
    private var travelEnd = 0f
    private var viewportLeft = 0f
    private var viewportRight = 0f
    private var capsuleScale = 1f
    private val samples = ArrayDeque<Pair<Long, Float>>()
    private var fingerVelocity = 0f
    private var sampleAge = 0f
    private var lastOffset = Float.NaN
    private var localControlBounds: RectF? = null
    val controlBounds: RectF? get() = localControlBounds
    var touchX = 0f
        private set
    var touchY = 0f
        private set
    val selectionCenterX: Float? get() = if (selection.visible) selectionRect.centerX() else null
    val selectionVisible: Boolean get() = selection.visible
    val selectionDragActive: Boolean get() = dragging
    val progress: Float get() = interaction.progress
    val highlight: Float get() = interaction.highlightProgress
    val scaleX: Float get() {
        if (!selection.visible || selectionRect.width() <= 0f || viewportRight <= viewportLeft) return interaction.scaleX
        val halfWidth = selectionRect.width() / 2f
        val shear = abs(lensSkew) * selectionRect.height() * scaleY / 2f
        val space = min(selectionRect.centerX() - viewportLeft, viewportRight - selectionRect.centerX())
        return min(interaction.scaleX, ((space - shear) / halfWidth).coerceAtLeast(0.78f))
    }
    val scaleY: Float get() = interaction.scaleY
    val lensSkew: Float get() = interaction.lensSkew
    val running: Boolean get() = active && (selection.running || interaction.running)

    fun configure(width: Int, height: Int, pressEnabled: Boolean, animate: Boolean, dragStrength: Float, capsuleScale: Float, clickSpeed: Float) {
        this.width = width
        this.height = height
        this.animate = animate
        this.capsuleScale = capsuleScale
        this.clickSpeed = clickSpeed
        interaction.configure(animate && pressEnabled, dragStrength)
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

    fun setSelectionViewport(left: Float, right: Float) {
        if (viewportLeft == left && viewportRight == right) return
        viewportLeft = left
        viewportRight = right
        changed()
    }

    fun setControlBounds(rect: RectF?) {
        localControlBounds = rect?.takeUnless { it.isEmpty }?.let(::RectF)
        changed()
    }

    fun hitSelection(x: Float, y: Float): Boolean {
        if (!selection.visible || y !in 0f..height.toFloat()) return false
        val halfWidth = selectionRect.width() / 2f
        val halfHeight = selectionRect.height() / 2f
        val dx = abs(x - selectionRect.centerX() - lensSkew * (y - selectionRect.centerY())) / scaleX
        val dy = abs(y - selectionRect.centerY()) / scaleY
        if (dx > halfWidth || dy > halfHeight) return false
        val radius = minOf(halfWidth, halfHeight)
        val cornerX = (dx - (halfWidth - radius)).coerceAtLeast(0f)
        val cornerY = (dy - (halfHeight - radius)).coerceAtLeast(0f)
        return cornerX * cornerX + cornerY * cornerY <= radius * radius
    }

    fun setSelection(left: Float, top: Float, right: Float, bottom: Float) {
        if (width <= 0 || height <= 0) return
        val extraWidth = (right - left) * (capsuleScale - 1f) / 2f
        val extraHeight = (bottom - top) * (capsuleScale - 1f) / 2f
        hostSelection.set(left - extraWidth, top - extraHeight, right + extraWidth, bottom + extraHeight)
        if (dragging) return
        val hadSelection = selection.visible
        val previous = selection.targetCenter
        if (hadSelection && abs(hostSelection.centerX() / width - previous) > 0.0001f && !pressed) settlingDrag = false
        target(hostSelection.left, hostSelection.top, hostSelection.right, hostSelection.bottom, animate && active,
            if (settlingDrag) 1f else clickSpeed)
        if (hadSelection && abs(selection.targetCenter - previous) > 0.0001f && !pressed && active && animate) {
            interaction.press(interactive = false)
            interaction.release()
            changed()
        }
    }

    private fun target(left: Float, top: Float, right: Float, bottom: Float, animated: Boolean, speed: Float = 1f) {
        if (selection.target(left / width, top / height, right / width, bottom / height, animated, speed)) changed()
    }

    fun clearSelection() {
        if (!selection.visible && hostSelection.isEmpty && !dragging) return
        val wasDragging = dragging
        dragging = false
        settlingDrag = false
        hostSelection.setEmpty()
        selection.clear()
        if (wasDragging) {
            pressed = false
            interaction.cancel()
        }
        changed()
    }

    fun beginSelectionDrag(x: Float, eventTime: Long): Boolean {
        if (!active || !selection.visible || width <= 0) return false
        // Pick up the currently displayed capsule, including an interrupted settling animation.
        selection.target(selectionRect.left / width, selection.top, selectionRect.right / width, selection.bottom, false)
        startX = x
        dragOffset = x - selection.center * width
        samples.clear()
        samples.addLast(eventTime to selection.center * width)
        fingerVelocity = 0f
        sampleAge = 0f
        dragging = true
        settlingDrag = false
        changed()
        return true
    }

    fun moveSelectionDrag(x: Float, eventTime: Long) {
        if (!dragging || hostSelection.isEmpty) return
        val half = hostSelection.width() / 2f
        val minimum = if (travelEnd > travelStart) travelStart else half
        val maximum = if (travelEnd > travelStart) travelEnd else width - half
        val center = (x - dragOffset).coerceIn(minimum, maximum.coerceAtLeast(minimum))
        if (samples.isEmpty() || eventTime > samples.last.first) {
            samples.addLast(eventTime to center)
            while (samples.size > 2 && eventTime - samples.first.first > 80L) samples.removeFirst()
            val first = samples.first
            val elapsed = eventTime - first.first
            fingerVelocity = if (elapsed in 1L..100L) (center - first.second) * 1000f / elapsed else 0f
            sampleAge = 0f
        }
        // Never put a spring between the finger and the displayed/committed center.
        target(center - half, hostSelection.top, center + half, hostSelection.bottom, false)
        interaction.drag(x - startX)
        changed()
    }

    fun cancelSelectionDrag() {
        if (!dragging && !pressed) return
        val wasDragging = dragging
        dragging = false
        settlingDrag = false
        pressed = false
        fingerVelocity = 0f
        samples.clear()
        if (wasDragging && !hostSelection.isEmpty) target(hostSelection.left, hostSelection.top, hostSelection.right, hostSelection.bottom, active && animate)
        interaction.cancel()
        changed()
    }

    fun releaseSelectionDrag(dropped: Boolean = true) {
        if (!dragging) return
        dragging = false
        settlingDrag = dropped
        pressed = false
        var velocity = if (dropped && sampleAge <= 0.08f) fingerVelocity / width else 0f
        val releaseRate = if (dropped) DRAG_RELEASE_RATE else 1f
        val targetCenter = hostSelection.centerX()
        val atStart = abs(targetCenter - travelStart) < 1f
        val atEnd = abs(targetCenter - travelEnd) < 1f
        // A terminal slot has no outward travel. Keep the release within the critically
        // damped approach instead of feeding a hidden overshoot back into lens distortion.
        if (atStart) velocity = maxOf(velocity, -sqrt(1000f) * releaseRate * (selection.center - travelStart / width).coerceAtLeast(0f))
        if (atEnd) velocity = minOf(velocity, sqrt(1000f) * releaseRate * (travelEnd / width - selection.center).coerceAtLeast(0f))
        if (!hostSelection.isEmpty) {
            selection.release(hostSelection.left / width, hostSelection.top / height,
                hostSelection.right / width, hostSelection.bottom / height,
                velocity.coerceIn(-300f * density / width, 300f * density / width) / releaseRate, active && animate,
                if (dropped) 1f else clickSpeed)
        }
        samples.clear()
        interaction.release()
        changed()
    }

    fun observeTouch(action: Int, x: Float, y: Float) {
        if (!active) return
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                if (x !in 0f..width.toFloat() || y !in 0f..height.toFloat()) return
                pressed = true
                settlingDrag = false
                updateTouch(x, y)
                interaction.press(selection = selection.visible)
            }
            MotionEvent.ACTION_MOVE -> if (pressed) {
                updateTouch(x, y)
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) releaseSelectionDrag()
                if (pressed) {
                    pressed = false
                    if (selection.visible) interaction.release() else interaction.cancel()
                }
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> cancelSelectionDrag()
        }
        changed()
    }

    fun advance(seconds: Float) {
        if (!active) return
        val releaseRate = if (settlingDrag) DRAG_RELEASE_RATE else 1f
        selection.advance(seconds * releaseRate)
        sampleAge += seconds
        val velocity = if (dragging) {
            if (sampleAge <= 0.05f) fingerVelocity else 0f
        } else selection.velocity * width * releaseRate
        interaction.advance(seconds * releaseRate, GlassInteractionMotion.normalizedVelocity(velocity, travelEnd - travelStart),
            abs(selection.center - selection.targetCenter) <
                ((travelEnd - travelStart) / width.coerceAtLeast(1) * 0.025f).coerceAtLeast(0.0005f))
        if (!selection.running && !interaction.running) settlingDrag = false
        changed()
    }

    fun reset() {
        pressed = false
        dragging = false
        settlingDrag = false
        samples.clear()
        if (!hostSelection.isEmpty && width > 0 && height > 0) {
            target(hostSelection.left, hostSelection.top, hostSelection.right, hostSelection.bottom, false)
        }
        selection.settle()
        interaction.reset()
        changed()
    }

    private fun updateTouch(x: Float, y: Float) {
        val bounds = localControlBounds
        touchX = x.coerceIn(bounds?.left ?: 0f, bounds?.right ?: width.toFloat())
        touchY = y.coerceIn(bounds?.top ?: 0f, bounds?.bottom ?: height.toFloat())
    }

    private fun changed() {
        selectionRect.set(selection.left * width, selection.top * height, selection.right * width, selection.bottom * height)
        if (selection.visible && travelEnd > travelStart) {
            val center = selectionRect.centerX()
            val edge = SELECTION_OVERSHOOT_DP * density
            selectionRect.offset(center.coerceIn(travelStart - edge, travelEnd + edge) - center, 0f)
        }
        val offset = if (selection.visible) GlassInteractionMotion.rubberBand(interaction.dragDistance, width.toFloat(), density) else 0f
        if (lastOffset != offset) { lastOffset = offset; onInteractionOffset?.invoke(offset) }
        onChanged?.invoke()
    }

    fun close() {
        reset()
        onChanged = null
        onInteractionOffset = null
    }

    companion object {
        private const val DRAG_RELEASE_RATE = 0.85f
        const val SELECTION_OVERSHOOT_DP = 4f
        const val MAX_SCALE_X = 1.95f
        const val MAX_SCALE_Y = 1.8f
        const val MAX_SKEW = 0f
    }
}
