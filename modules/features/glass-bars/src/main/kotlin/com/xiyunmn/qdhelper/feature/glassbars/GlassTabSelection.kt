package com.xiyunmn.qdhelper.feature.glassbars

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
    private val glass: GlassBarMotion? = null,
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
    private data class TabLayout(val view: View, val left: Int, val width: Int, val visibility: Int, val enabled: Boolean)
    private var gestureLayout = emptyList<TabLayout>()
    private var gestureWidth = 0

    private fun gestureValid(row: LinearLayout): Boolean = row === strip.getChildAt(0) &&
        bar.width == gestureWidth && row.childCount == gestureLayout.size && gestureLayout.withIndex().all { (index, item) ->
            val view = row.getChildAt(index)
            view === item.view && view.left == item.left && view.width == item.width &&
                view.visibility == item.visibility && view.isEnabled == item.enabled
        }

    private fun displayedX(fallback: Float): Float {
        val center = glass?.selectionCenterX ?: return fallback
        bar.getLocationOnScreen(barLocation)
        return barLocation[0] + center
    }

    private fun tabScreenLeft(row: LinearLayout, tab: View): Float {
        row.getLocationOnScreen(tabLocation)
        return tabLocation[0] + tab.left + tab.translationX - row.scrollX
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
        dragged = false
        if (releaseVisual) glass?.cancelSelectionDrag()
    }

    fun observeTouch(action: Int, x: Float, y: Float, eventTime: Long = android.os.SystemClock.uptimeMillis()) {
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                cancelTouch()
                val row = strip.getChildAt(0) as? LinearLayout ?: return
                val index = FeatureHooks.invokeOrNull(currentItem, strip) as? Int ?: return
                val tab = row.takeIf { index in 0 until it.childCount }?.getChildAt(index) ?: return
                val left = tabScreenLeft(row, tab)
                bar.getLocationOnScreen(barLocation)
                if (tab.visibility == View.VISIBLE && tab.isEnabled && x >= left && x < left + tab.width &&
                    y >= barLocation[1] && y < barLocation[1] + bar.height) {
                    gestureRow = row
                    gestureWidth = bar.width
                    gestureLayout = (0 until row.childCount).map { position ->
                        val view = row.getChildAt(position)
                        TabLayout(view, view.left, view.width, view.visibility, view.isEnabled)
                    }
                    startX = x
                    dragOffsetX = x - left - tab.width / 2f
                    glass?.beginSelectionDrag(x - barLocation[0], eventTime)
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
                    glass?.moveSelectionDrag(x - barLocation[0], eventTime)
                }
            }
            MotionEvent.ACTION_UP -> {
                val row = gestureRow
                if (!dragged || row == null || !gestureValid(row)) {
                    cancelTouch()
                    return
                }
                // UP may contain a newer position than the last MOVE; commit that same center.
                bar.getLocationOnScreen(barLocation)
                glass?.moveSelectionDrag(x - barLocation[0], eventTime)
                val target = nearestTab(row, displayedX(x - dragOffsetX))
                cancelTouch(releaseVisual = false)
                val current = FeatureHooks.invokeOrNull(currentItem, strip) as? Int ?: -1
                if (target != null && row.indexOfChild(target) != current) target.performClick()
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> cancelTouch()
        }
    }

    fun update(glass: GlassBarMotion) {
        gestureRow?.let { if (!gestureValid(it)) cancelTouch() }
        // PagerSlidingTabStrip is a HorizontalScrollView with one LinearLayout child on all five
        // supported versions. Read it anew because host/config rebuilds can replace the row.
        val row = strip.getChildAt(0) as? LinearLayout
        val index = FeatureHooks.invokeOrNull(currentItem, strip) as? Int ?: -1
        val tab = row?.takeIf { index in 0 until it.childCount }?.getChildAt(index)
        if (tab == null || tab.visibility != View.VISIBLE || tab.width <= 0) {
            glass.clearSelection()
            return
        }
        bar.getLocationOnScreen(barLocation)
        val tabLeft = tabScreenLeft(requireNotNull(row), tab) - barLocation[0]
        val left = (tabLeft + inset).coerceAtLeast(inset)
        val right = (tabLeft + tab.width - inset).coerceAtMost(bar.width - inset)
        var firstCenter = Float.POSITIVE_INFINITY
        var lastCenter = Float.NEGATIVE_INFINITY
        for (childIndex in 0 until row.childCount) {
            val child = row.getChildAt(childIndex)
            if (child.visibility != View.VISIBLE || !child.isEnabled || child.width <= 0) continue
            val center = tabScreenLeft(row, child) - barLocation[0] + child.width / 2f
            firstCenter = minOf(firstCenter, center)
            lastCenter = maxOf(lastCenter, center)
        }
        glass.setSelectionTravelRange(firstCenter, lastCenter)
        glass.setSelection(left, inset, right, bar.height - inset)
    }
}
