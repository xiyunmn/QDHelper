package com.xiyunmn.qdhelper.shared.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Drag-to-reorder vertical list built purely on framework Views + [MotionEvent] —
 * no RecyclerView / ItemTouchHelper (the module has zero androidx dependencies).
 *
 * Each row shows caller-supplied content plus a trailing "≡" drag handle; only the
 * handle starts a drag, so row-internal controls (e.g. a visibility Switch) and the
 * outer ScrollView keep working. During a drag the dragged row floats with the
 * finger while the other rows shift by one slot; the new order is committed on drop.
 */
class DragReorderList(
    private val activity: Activity,
    private val handleColor: Int = UiColors.Handle,
    private val elevatedColor: Int = UiColors.DragElevated,
    private val dividerColor: Int = UiColors.Divider,
) : LinearLayout(activity) {

    private data class Row(val key: String, val wrapper: LinearLayout, val handle: View)

    private val rows = mutableListOf<Row>()
    private var onReorder: ((List<String>) -> Unit)? = null

    // Active drag state
    private var dragging: LinearLayout? = null
    private var dragFromIndex = -1
    private var dragStartRawY = 0f
    private var dragRowHeight = 0

    init {
        orientation = VERTICAL
    }

    fun setRows(
        keys: List<String>,
        renderContent: (key: String) -> View,
        onReorder: (List<String>) -> Unit,
    ) {
        this.onReorder = onReorder
        removeAllViews()
        rows.clear()
        keys.forEachIndexed { index, key ->
            val handle = DragHandleView(activity, handleColor).apply {
                contentDescription = "拖动排序"
            }
            val contentRow = LinearLayout(activity).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = activity.dp(52)
                addView(handle, LayoutParams(activity.dp(40), activity.dp(44)).apply {
                    marginStart = activity.dp(6)
                })
                addView(
                    renderContent(key),
                    LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
            }
            val rowDivider = View(activity).apply {
                setBackgroundColor(dividerColor)
                layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                    marginStart = activity.dp(16)
                }
            }
            val wrapper = LinearLayout(activity).apply {
                orientation = VERTICAL
                addView(contentRow, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(rowDivider)
            }
            attachHandleTouch(handle, wrapper)
            rows.add(Row(key, wrapper, handle))
            addView(wrapper, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        refreshDividerVisibility()
    }

    private fun refreshDividerVisibility() {
        val last = childCount - 1
        for (index in 0 until childCount) {
            val wrapper = getChildAt(index) as? LinearLayout ?: continue
            val rowDivider = wrapper.getChildAt(1) ?: continue
            rowDivider.visibility = if (index == last) View.INVISIBLE else View.VISIBLE
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachHandleTouch(handle: View, wrapper: LinearLayout) {
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    beginDrag(wrapper, event.rawY)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragging === wrapper) {
                        updateDrag(event.rawY)
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging === wrapper) {
                        endDrag()
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
    }

    private fun beginDrag(wrapper: LinearLayout, rawY: Float) {
        parent?.requestDisallowInterceptTouchEvent(true)
        dragging = wrapper
        dragFromIndex = indexOfChild(wrapper)
        dragStartRawY = rawY
        dragRowHeight = wrapper.height.takeIf { it > 0 } ?: activity.dp(53)
        wrapper.translationZ = activity.dp(6).toFloat()
        wrapper.alpha = 0.96f
        wrapper.background = rounded(elevatedColor, 10, activity)
    }

    private fun updateDrag(rawY: Float) {
        val wrapper = dragging ?: return
        if (dragRowHeight <= 0) return
        val dy = rawY - dragStartRawY
        wrapper.translationY = dy
        val target = (dragFromIndex + (dy / dragRowHeight).roundToInt()).coerceIn(0, childCount - 1)
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child === wrapper) continue
            val shift = when {
                dragFromIndex < target && index in (dragFromIndex + 1)..target -> -dragRowHeight
                dragFromIndex > target && index in target until dragFromIndex -> dragRowHeight
                else -> 0
            }
            if (abs(child.translationY - shift) > 0.5f) {
                child.animate().cancel()
                child.animate().translationY(shift.toFloat()).setDuration(120L).start()
            }
        }
    }

    private fun endDrag() {
        val wrapper = dragging ?: return
        parent?.requestDisallowInterceptTouchEvent(false)
        val dy = wrapper.translationY
        val target = if (dragRowHeight > 0) {
            (dragFromIndex + (dy / dragRowHeight).roundToInt()).coerceIn(0, childCount - 1)
        } else {
            dragFromIndex
        }
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            child.animate().cancel()
            child.translationY = 0f
        }
        wrapper.translationZ = 0f
        wrapper.alpha = 1f
        wrapper.background = null
        dragging = null

        if (target != dragFromIndex) {
            val moved = rows.removeAt(dragFromIndex)
            rows.add(target, moved)
            removeViewAt(dragFromIndex)
            addView(moved.wrapper, target)
            refreshDividerVisibility()
            onReorder?.invoke(rows.map { it.key })
        }
        dragFromIndex = -1
    }
}

private class DragHandleView(context: Context, color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        strokeWidth = context.dp(2).toFloat()
        strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        val left = width * 0.3f
        val right = width * 0.7f
        for (index in -1..1) {
            val y = height / 2f + index * height * 0.14f
            canvas.drawLine(left, y, right, y, paint)
        }
    }
}
