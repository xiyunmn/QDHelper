package com.xiyunmn.qdhelper.feature.readerfont.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.min

internal enum class IconKind {
    IMPORT,
    MANAGE,
    DELETE,
    CLOSE,
    CHECK,
    UP,
    DOWN,
}

internal class IconButtonView(
    context: Context,
    private val kind: IconKind,
    private val iconColor: Int,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = dp(context, 2).toFloat().coerceAtLeast(1f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    init {
        isClickable = true
        isFocusable = true
        val attrs = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
        background = attrs.getDrawable(0)
        attrs.recycle()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (Color.alpha(iconColor) == 0) return
        paint.color = iconColor
        paint.style = Paint.Style.STROKE
        val cx = width / 2f
        val cy = height / 2f
        val size = min(width, height) * 0.42f
        when (kind) {
            IconKind.IMPORT -> drawImport(canvas, cx, cy, size)
            IconKind.MANAGE -> drawManage(canvas, cx, cy, size)
            IconKind.DELETE -> drawDelete(canvas, cx, cy, size)
            IconKind.CLOSE -> drawClose(canvas, cx, cy, size)
            IconKind.CHECK -> drawCheck(canvas, cx, cy, size)
            IconKind.UP -> drawArrow(canvas, cx, cy, size, up = true)
            IconKind.DOWN -> drawArrow(canvas, cx, cy, size, up = false)
        }
    }

    private fun drawImport(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val left = cx - size * 0.58f
        val right = cx + size * 0.58f
        val bottom = cy + size * 0.44f
        canvas.drawLine(left, bottom, right, bottom, paint)
        canvas.drawLine(left, bottom, left, cy + size * 0.12f, paint)
        canvas.drawLine(right, bottom, right, cy + size * 0.12f, paint)
        canvas.drawLine(cx, cy - size * 0.58f, cx, cy + size * 0.16f, paint)
        canvas.drawLine(cx, cy + size * 0.16f, cx - size * 0.24f, cy - size * 0.08f, paint)
        canvas.drawLine(cx, cy + size * 0.16f, cx + size * 0.24f, cy - size * 0.08f, paint)
    }

    private fun drawManage(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val left = cx - size * 0.62f
        val right = cx + size * 0.62f
        val ys = floatArrayOf(cy - size * 0.38f, cy, cy + size * 0.38f)
        val knobs = floatArrayOf(cx - size * 0.18f, cx + size * 0.24f, cx - size * 0.02f)
        ys.forEachIndexed { index, y ->
            canvas.drawLine(left, y, right, y, paint)
            paint.style = Paint.Style.FILL
            canvas.drawCircle(knobs[index], y, size * 0.11f, paint)
            paint.style = Paint.Style.STROKE
        }
    }

    private fun drawDelete(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val left = cx - size * 0.42f
        val right = cx + size * 0.42f
        val top = cy - size * 0.22f
        val bottom = cy + size * 0.48f
        canvas.drawLine(left, top, right, top, paint)
        canvas.drawLine(left + size * 0.08f, top, left + size * 0.16f, bottom, paint)
        canvas.drawLine(right - size * 0.08f, top, right - size * 0.16f, bottom, paint)
        canvas.drawLine(left + size * 0.16f, bottom, right - size * 0.16f, bottom, paint)
        canvas.drawLine(cx - size * 0.2f, cy - size * 0.42f, cx + size * 0.2f, cy - size * 0.42f, paint)
    }

    private fun drawClose(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        canvas.drawLine(cx - size * 0.42f, cy - size * 0.42f, cx + size * 0.42f, cy + size * 0.42f, paint)
        canvas.drawLine(cx + size * 0.42f, cy - size * 0.42f, cx - size * 0.42f, cy + size * 0.42f, paint)
    }

    private fun drawCheck(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        canvas.drawLine(cx - size * 0.44f, cy, cx - size * 0.12f, cy + size * 0.32f, paint)
        canvas.drawLine(cx - size * 0.12f, cy + size * 0.32f, cx + size * 0.48f, cy - size * 0.34f, paint)
    }

    private fun drawArrow(canvas: Canvas, cx: Float, cy: Float, size: Float, up: Boolean) {
        val direction = if (up) -1f else 1f
        val tipY = cy + direction * size * 0.44f
        val tailY = cy - direction * size * 0.44f
        val headY = cy + direction * size * 0.16f
        canvas.drawLine(cx, tailY, cx, tipY, paint)
        canvas.drawLine(cx, tipY, cx - size * 0.28f, headY, paint)
        canvas.drawLine(cx, tipY, cx + size * 0.28f, headY, paint)
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()
}
