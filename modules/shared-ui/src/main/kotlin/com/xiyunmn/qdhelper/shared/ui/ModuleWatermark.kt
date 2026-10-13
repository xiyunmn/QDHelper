package com.xiyunmn.qdhelper.shared.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.View

/** Fixed to the module window, independently of content scrolling, layout and animations. */
fun View.installModuleWatermark(darkSurface: Boolean? = null): AutoCloseable {
    val watermark = ModuleWatermark(resources.displayMetrics.density, darkSurface)
    var windowRoot: View? = null
    fun resize() {
        windowRoot?.let { watermark.setBounds(0, 0, it.width, it.height) }
    }
    val layout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> resize() }
    fun detach() {
        windowRoot?.let {
            it.removeOnLayoutChangeListener(layout)
            it.overlay.remove(watermark)
        }
        windowRoot = null
    }
    fun attach() {
        // Resolve after attachment: before setContentView/show, rootView can still be the content.
        if (windowRoot === rootView) return
        detach()
        windowRoot = rootView
        resize()
        windowRoot?.addOnLayoutChangeListener(layout)
        windowRoot?.overlay?.add(watermark)
    }
    val attachment = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = attach()
        override fun onViewDetachedFromWindow(view: View) = detach()
    }
    addOnAttachStateChangeListener(attachment)
    if (isAttachedToWindow) attach()
    return AutoCloseable {
        removeOnAttachStateChangeListener(attachment)
        detach()
    }
}

private class ModuleWatermark(private val density: Float, private val darkSurface: Boolean?) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * density
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        paint.color = if (darkSurface ?: UiColors.IsNight) Color.WHITE else Color.BLACK
        paint.alpha = 32
        val stepX = 166f * density
        val stepY = 96f * density
        val columns = (bounds.width() / stepX).toInt() + 2
        val rows = (bounds.height() / stepY).toInt() + 1
        for (row in 0 until rows) for (column in -1 until columns) {
            // Coordinate-derived offsets stay identical across redraws and window height changes.
            val rowShift = variation(row, 0, 1) * stepX
            val x = bounds.left + 72f * density + column * stepX + rowShift + variation(row, column, 2) * 24f * density
            val y = bounds.top + 44f * density + row * stepY + variation(row, column, 3) * 22f * density
            val angle = -20f + variation(row, column, 4) * 12f
            val checkpoint = canvas.save()
            canvas.rotate(angle, x, y)
            canvas.drawText("免费模块，禁止倒卖", x, y, paint)
            canvas.restoreToCount(checkpoint)
        }
    }

    /** Stable noise in [-0.5, 0.5]; no time, scroll offset or mutable random seed is involved. */
    private fun variation(row: Int, column: Int, salt: Int): Float {
        var value = row * 73856093 xor column * 19349663 xor salt * 83492791
        value = (value xor (value ushr 13)) * 1274126177
        return ((value xor (value ushr 16)) and 0xffff) / 65535f - .5f
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
