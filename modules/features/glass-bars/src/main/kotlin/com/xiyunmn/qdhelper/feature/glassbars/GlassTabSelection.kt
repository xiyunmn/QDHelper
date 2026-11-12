package com.xiyunmn.qdhelper.feature.glassbars

import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.LinearLayout
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.shared.ui.glass.GlassBarMotion

/** Uses native tab clicks so cloud tabs, hidden tabs and host display-index mapping stay intact. */
internal class GlassTabSelection(
    private val bar: ViewGroup,
    private val strip: ViewGroup,
    private val glass: GlassBarMotion,
    private val previewChanged: () -> Unit,
    private val selectionConfirmed: () -> Boolean,
) {
    private val currentItem = FeatureHooks.publicMethodOrNull(strip.javaClass, "getCurrentItem", 0)
    private val barLocation = IntArray(2)
    private val tabLocation = IntArray(2)
    private val inset = 4f * bar.resources.displayMetrics.density
    private val slop = ViewConfiguration.get(bar.context).scaledTouchSlop
    private var gestureRow: LinearLayout? = null
    private var startX = 0f
    private var dragged = false
    private var dragOffsetX = 0f
    private data class TabLayout(
        val view: View, val left: Int, val width: Int, val visibility: Int, val enabled: Boolean,
        var selectionBounds: RectF?,
    )
    private var gestureLayout = emptyList<TabLayout>()
    private var gestureWidth = 0
    private var gestureFrom = -1
    private var preview: View? = null
    private var releaseLogCount = 0

    private fun gestureValid(row: LinearLayout): Boolean = row === strip.getChildAt(0) &&
        bar.width == gestureWidth && row.childCount == gestureLayout.size && gestureLayout.withIndex().all { (index, item) ->
            val view = row.getChildAt(index)
            view === item.view && view.left == item.left && view.width == item.width &&
                view.visibility == item.visibility && view.isEnabled == item.enabled
        }

    private fun displayedX(fallback: Float): Float {
        val center = glass.selectionCenterX ?: return fallback
        bar.getLocationOnScreen(barLocation)
        return barLocation[0] + center
    }

    private fun tabScreenLeft(row: LinearLayout, tab: View): Float {
        row.getLocationOnScreen(tabLocation)
        return tabLocation[0] + tab.left + tab.translationX - row.scrollX
    }

    private fun selectionBounds(row: LinearLayout, tab: View): RectF? {
        if (tab.visibility != View.VISIBLE || tab.width <= 0 || bar.width <= 0 || bar.height <= 0) return null
        bar.getLocationOnScreen(barLocation)
        val tabLeft = tabScreenLeft(row, tab) - barLocation[0]
        val bounds = RectF((tabLeft + inset).coerceAtLeast(inset), inset,
            (tabLeft + tab.width - inset).coerceAtMost(bar.width - inset), bar.height - inset)
        return bounds.takeUnless { it.isEmpty }
    }

    private fun nearestTab(row: LinearLayout, x: Float): View? {
        var nearest: View? = null
        var distance = Float.MAX_VALUE
        row.getLocationOnScreen(tabLocation)
        val rowLeft = tabLocation[0] - row.scrollX
        for (index in 0 until row.childCount) {
            val tab = row.getChildAt(index)
            if (tab.visibility != View.VISIBLE || !tab.isEnabled || tab.width <= 0) continue
            val delta = kotlin.math.abs(x - rowLeft - tab.left - tab.translationX - tab.width / 2f)
            if (delta < distance) { distance = delta; nearest = tab }
        }
        return nearest
    }

    fun cancelTouch(releaseVisual: Boolean = true) {
        gestureRow = null
        gestureLayout = emptyList()
        gestureFrom = -1
        dragged = false
        preview = null
        if (releaseVisual) {
            glass.cancelSelectionDrag()
        }
    }

    fun observeTouch(action: Int, x: Float, y: Float, eventTime: Long = android.os.SystemClock.uptimeMillis()) {
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                cancelTouch()
                val row = strip.getChildAt(0) as? LinearLayout ?: return
                val index = FeatureHooks.invokeOrNull(currentItem, strip) as? Int ?: return
                val tab = row.takeIf { index in 0 until it.childCount }?.getChildAt(index) ?: return
                bar.getLocationOnScreen(barLocation)
                if (tab.visibility == View.VISIBLE && tab.isEnabled &&
                    glass.hitSelection(x - barLocation[0], y - barLocation[1]) &&
                    glass.beginSelectionDrag(x - barLocation[0], eventTime)) {
                    gestureRow = row
                    gestureFrom = index
                    gestureWidth = bar.width
                    gestureLayout = (0 until row.childCount).map { position ->
                        val view = row.getChildAt(position)
                        TabLayout(view, view.left, view.width, view.visibility, view.isEnabled, selectionBounds(row, view))
                    }
                    startX = x
                    dragOffsetX = x - displayedX(x)
                    preview = nearestTab(row, displayedX(x))
                }
            }
            MotionEvent.ACTION_MOVE -> if (gestureRow != null) {
                if (!gestureValid(requireNotNull(gestureRow))) {
                    cancelTouch()
                    return
                }
                // Match the glass capsule: after pickup, vertical movement must not cancel the
                // horizontal preview or prevent release outside the bar.
                if (kotlin.math.abs(x - startX) > slop) dragged = true
                if (dragged) {
                    bar.getLocationOnScreen(barLocation)
                    glass.moveSelectionDrag(x - barLocation[0], eventTime)
                    val row = requireNotNull(gestureRow)
                    val center = displayedX(x)
                    val candidate = nearestTab(row, center)
                    val previous = preview
                    if (candidate != null && candidate !== previous) {
                        fun distance(tab: View) = kotlin.math.abs(center - tabScreenLeft(row, tab) - tab.width / 2f)
                        if (previous == null || distance(candidate) + inset < distance(previous)) {
                            preview = candidate
                            previewChanged()
                        }
                    }
                    gestureLayout.firstOrNull { it.view === preview }?.let {
                        it.selectionBounds = selectionBounds(row, it.view)
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                val row = gestureRow
                if (row != null && !gestureValid(row)) {
                    if (dragged) {
                        val from = gestureFrom
                        val targetIndex = gestureLayout.indexOfFirst { it.view === preview }
                        val targetBounds = gestureLayout.getOrNull(targetIndex)?.selectionBounds
                        val targetWidth = gestureWidth
                        val readback = FeatureHooks.invokeOrNull(currentItem, strip) as? Int ?: -1
                        val matched = targetIndex >= 0 && readback == targetIndex
                        // The host has already handled UP. A rebuilt layout may confirm a
                        // completed native switch, but must never receive a stale tab click.
                        cancelTouch(releaseVisual = !matched)
                        var geometryReady = false
                        var motionContinuity = false
                        if (matched) {
                            geometryReady = update(glass)
                            if (!geometryReady && targetBounds != null && bar.width == targetWidth) {
                                glass.setSelection(targetBounds.left, targetBounds.top, targetBounds.right, targetBounds.bottom)
                            }
                            motionContinuity = glass.selectionVisible && glass.selectionDragActive
                            glass.releaseSelectionDrag()
                        }
                        val accepted = matched && selectionConfirmed()
                        logRelease(from, targetIndex, readback, accepted, layoutValid = false,
                            geometryReady = geometryReady, motionContinuity = motionContinuity)
                    } else cancelTouch()
                    return
                }
                if (!dragged || row == null) {
                    cancelTouch(releaseVisual = false)
                    update(glass)
                    if (row != null) glass.releaseSelectionDrag(dropped = false)
                    return
                }
                // UP may contain a newer position than the last MOVE; commit that same center.
                bar.getLocationOnScreen(barLocation)
                glass.moveSelectionDrag(x - barLocation[0], eventTime)
                val target = nearestTab(row, displayedX(x - dragOffsetX))
                val targetIndex = target?.let(row::indexOfChild) ?: -1
                val targetBounds = target?.let { selectionBounds(row, it) }
                val targetWidth = bar.width
                val from = gestureFrom
                cancelTouch(releaseVisual = false)
                val current = FeatureHooks.invokeOrNull(currentItem, strip) as? Int ?: -1
                if (target != null && targetIndex != current) target.performClick()
                val readback = FeatureHooks.invokeOrNull(currentItem, strip) as? Int ?: -1
                val matched = targetIndex >= 0 && readback == targetIndex
                val geometryReady = update(glass)
                if (matched && !geometryReady && targetBounds != null && bar.width == targetWidth) {
                    glass.setSelection(targetBounds.left, targetBounds.top, targetBounds.right, targetBounds.bottom)
                }
                val motionContinuity = glass.selectionVisible && glass.selectionDragActive
                glass.releaseSelectionDrag(dropped = matched)
                val accepted = matched && selectionConfirmed()
                logRelease(from, targetIndex, readback, accepted, layoutValid = true,
                    geometryReady = geometryReady, motionContinuity = motionContinuity)
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> cancelTouch()
        }
    }

    fun update(glass: GlassBarMotion): Boolean {
        // Input handlers validate the captured layout before acting. A draw-side refresh
        // during native UP must not erase the completed drag before its page readback.
        // PagerSlidingTabStrip is a HorizontalScrollView with one LinearLayout child on all five
        // supported versions. Read it anew because host/config rebuilds can replace the row.
        val row = strip.getChildAt(0) as? LinearLayout
        val index = FeatureHooks.invokeOrNull(currentItem, strip) as? Int ?: -1
        val tab = row?.takeIf { index in 0 until it.childCount }?.getChildAt(index)
        val bounds = if (row != null && tab != null) selectionBounds(row, tab) else null
        if (bounds == null) {
            if (!(glass.selectionVisible && (glass.selectionDragActive || glass.running))) glass.clearSelection()
            return false
        }
        val currentRow = requireNotNull(row)
        var firstCenter = Float.POSITIVE_INFINITY
        var lastCenter = Float.NEGATIVE_INFINITY
        for (childIndex in 0 until currentRow.childCount) {
            val child = currentRow.getChildAt(childIndex)
            if (child.visibility != View.VISIBLE || !child.isEnabled || child.width <= 0) continue
            val center = tabScreenLeft(currentRow, child) - barLocation[0] + child.width / 2f
            firstCenter = minOf(firstCenter, center)
            lastCenter = maxOf(lastCenter, center)
        }
        glass.setSelectionTravelRange(firstCenter, lastCenter)
        glass.setSelection(bounds.left, bounds.top, bounds.right, bounds.bottom)
        return true
    }

    private fun logRelease(from: Int, target: Int, readback: Int, accepted: Boolean, layoutValid: Boolean,
        geometryReady: Boolean, motionContinuity: Boolean,
    ) {
        if (releaseLogCount >= 12) return
        releaseLogCount++
        FeatureHooks.i("Glass drag release from=$from target=$target readback=$readback hapticAccepted=$accepted layoutValid=$layoutValid geometryReady=$geometryReady motionContinuity=$motionContinuity")
    }
}
