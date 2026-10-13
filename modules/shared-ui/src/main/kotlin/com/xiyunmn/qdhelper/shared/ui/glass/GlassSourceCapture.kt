package com.xiyunmn.qdhelper.shared.ui.glass

import android.content.Context
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup

/**
 * Uses the framework child-drawing path to reference the source's own display list.
 * Calling View.draw directly bypasses that path's scroll/display-list bookkeeping.
 * This drawing helper is never attached and never reparents the host view.
 */
internal class GlassSourceCapture(context: Context) : ViewGroup(context) {
    fun record(canvas: Canvas, source: View) {
        val save = canvas.save()
        canvas.translate(-source.x, -source.y)
        drawChild(canvas, source, source.drawingTime)
        canvas.restoreToCount(save)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) = Unit
}
