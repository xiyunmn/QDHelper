package com.xiyunmn.qdhelper.feature.startup

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.min

/**
 * 交互裁剪视图：fit-center 显示位图，叠加固定 9:20 竖版裁剪框，支持整体拖动与四角缩放（保持比例）。
 *
 * 坐标两套：view 坐标（显示/触摸）与 bitmap 坐标（裁剪落地）。[cropRectInBitmap] 把当前裁剪框换算回
 * 位图像素坐标，交给 [StartupImageProcessor.cropAndSave]。位图切换（旋转后）时重置裁剪框到最大内接 9:20。
 */
@SuppressLint("ViewConstructor")
internal class StartupCropView(context: Context) : View(context) {
    private val aspectW = 9f
    private val aspectH = 20f
    private val handleTouchSlop = dp(24f)
    private val minCropSize = dp(48f)

    private var bitmap: Bitmap? = null
    private val imageRect = RectF()
    private val cropRect = RectF()

    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xA6000000.toInt()
    }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = dp(2f)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x88FFFFFF.toInt()
        strokeWidth = dp(1f)
    }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = dp(3f)
    }

    private var activeCorner = Corner.NONE
    private var draggingBody = false
    private var lastX = 0f
    private var lastY = 0f

    private enum class Corner { NONE, TL, TR, BL, BR }

    fun setBitmap(newBitmap: Bitmap) {
        bitmap = newBitmap
        if (width > 0 && height > 0) {
            layoutImage()
            resetCrop()
            invalidate()
        } else {
            requestLayout()
        }
    }

    /**
     * 当前裁剪框换算到位图像素坐标。无位图或未布局时返回 null。
     */
    fun cropRectInBitmap(): RectF? {
        val bmp = bitmap ?: return null
        if (imageRect.isEmpty || cropRect.isEmpty) {
            return null
        }
        val scaleX = bmp.width / imageRect.width()
        val scaleY = bmp.height / imageRect.height()
        val left = (cropRect.left - imageRect.left) * scaleX
        val top = (cropRect.top - imageRect.top) * scaleY
        val right = (cropRect.right - imageRect.left) * scaleX
        val bottom = (cropRect.bottom - imageRect.top) * scaleY
        return RectF(
            left.coerceIn(0f, bmp.width.toFloat()),
            top.coerceIn(0f, bmp.height.toFloat()),
            right.coerceIn(0f, bmp.width.toFloat()),
            bottom.coerceIn(0f, bmp.height.toFloat()),
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (bitmap != null && w > 0 && h > 0) {
            layoutImage()
            resetCrop()
        }
    }

    private fun layoutImage() {
        val bmp = bitmap ?: return
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        val scale = min(viewW / bmp.width, viewH / bmp.height)
        val drawW = bmp.width * scale
        val drawH = bmp.height * scale
        val left = (viewW - drawW) / 2f
        val top = (viewH - drawH) / 2f
        imageRect.set(left, top, left + drawW, top + drawH)
    }

    private fun resetCrop() {
        if (imageRect.isEmpty) {
            return
        }
        // 在图片内接一个最大的 9:20 矩形，居中。
        var cropW = imageRect.width()
        var cropH = cropW * aspectH / aspectW
        if (cropH > imageRect.height()) {
            cropH = imageRect.height()
            cropW = cropH * aspectW / aspectH
        }
        val cx = imageRect.centerX()
        val cy = imageRect.centerY()
        cropRect.set(cx - cropW / 2f, cy - cropH / 2f, cx + cropW / 2f, cy + cropH / 2f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return
        canvas.drawBitmap(bmp, null, imageRect, imagePaint)
        // 四周压暗，突出裁剪框。
        canvas.drawRect(imageRect.left, imageRect.top, imageRect.right, cropRect.top, dimPaint)
        canvas.drawRect(imageRect.left, cropRect.bottom, imageRect.right, imageRect.bottom, dimPaint)
        canvas.drawRect(imageRect.left, cropRect.top, cropRect.left, cropRect.bottom, dimPaint)
        canvas.drawRect(cropRect.right, cropRect.top, imageRect.right, cropRect.bottom, dimPaint)
        // 三分网格。
        val thirdW = cropRect.width() / 3f
        val thirdH = cropRect.height() / 3f
        canvas.drawLine(cropRect.left + thirdW, cropRect.top, cropRect.left + thirdW, cropRect.bottom, gridPaint)
        canvas.drawLine(cropRect.left + thirdW * 2f, cropRect.top, cropRect.left + thirdW * 2f, cropRect.bottom, gridPaint)
        canvas.drawLine(cropRect.left, cropRect.top + thirdH, cropRect.right, cropRect.top + thirdH, gridPaint)
        canvas.drawLine(cropRect.left, cropRect.top + thirdH * 2f, cropRect.right, cropRect.top + thirdH * 2f, gridPaint)
        canvas.drawRect(cropRect, framePaint)
        drawCorners(canvas)
    }

    private fun drawCorners(canvas: Canvas) {
        val len = dp(18f)
        // TL
        canvas.drawLine(cropRect.left, cropRect.top, cropRect.left + len, cropRect.top, cornerPaint)
        canvas.drawLine(cropRect.left, cropRect.top, cropRect.left, cropRect.top + len, cornerPaint)
        // TR
        canvas.drawLine(cropRect.right, cropRect.top, cropRect.right - len, cropRect.top, cornerPaint)
        canvas.drawLine(cropRect.right, cropRect.top, cropRect.right, cropRect.top + len, cornerPaint)
        // BL
        canvas.drawLine(cropRect.left, cropRect.bottom, cropRect.left + len, cropRect.bottom, cornerPaint)
        canvas.drawLine(cropRect.left, cropRect.bottom, cropRect.left, cropRect.bottom - len, cornerPaint)
        // BR
        canvas.drawLine(cropRect.right, cropRect.bottom, cropRect.right - len, cropRect.bottom, cornerPaint)
        canvas.drawLine(cropRect.right, cropRect.bottom, cropRect.right, cropRect.bottom - len, cornerPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                activeCorner = hitCorner(event.x, event.y)
                draggingBody = activeCorner == Corner.NONE && cropRect.contains(event.x, event.y)
                return activeCorner != Corner.NONE || draggingBody
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY
                lastX = event.x
                lastY = event.y
                if (activeCorner != Corner.NONE) {
                    resizeCorner(dx, dy)
                    invalidate()
                    return true
                }
                if (draggingBody) {
                    moveCrop(dx, dy)
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeCorner = Corner.NONE
                draggingBody = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun hitCorner(x: Float, y: Float): Corner {
        return when {
            near(x, y, cropRect.left, cropRect.top) -> Corner.TL
            near(x, y, cropRect.right, cropRect.top) -> Corner.TR
            near(x, y, cropRect.left, cropRect.bottom) -> Corner.BL
            near(x, y, cropRect.right, cropRect.bottom) -> Corner.BR
            else -> Corner.NONE
        }
    }

    private fun near(x: Float, y: Float, cx: Float, cy: Float): Boolean {
        return abs(x - cx) <= handleTouchSlop && abs(y - cy) <= handleTouchSlop
    }

    private fun moveCrop(dx: Float, dy: Float) {
        var ndx = dx
        var ndy = dy
        if (cropRect.left + ndx < imageRect.left) ndx = imageRect.left - cropRect.left
        if (cropRect.right + ndx > imageRect.right) ndx = imageRect.right - cropRect.right
        if (cropRect.top + ndy < imageRect.top) ndy = imageRect.top - cropRect.top
        if (cropRect.bottom + ndy > imageRect.bottom) ndy = imageRect.bottom - cropRect.bottom
        cropRect.offset(ndx, ndy)
    }

    /**
     * 以对角为锚点缩放，宽随高按 9:20 联动，限制在图片内且不小于 [minCropSize]。
     */
    private fun resizeCorner(dx: Float, dy: Float) {
        // 以高度变化为主驱动（竖版），宽度按比例回算。
        val newRect = RectF(cropRect)
        when (activeCorner) {
            Corner.TL -> {
                newRect.top += dy
                newRect.left = newRect.right - (newRect.bottom - newRect.top) * aspectW / aspectH
            }
            Corner.TR -> {
                newRect.top += dy
                newRect.right = newRect.left + (newRect.bottom - newRect.top) * aspectW / aspectH
            }
            Corner.BL -> {
                newRect.bottom += dy
                newRect.left = newRect.right - (newRect.bottom - newRect.top) * aspectW / aspectH
            }
            Corner.BR -> {
                newRect.bottom += dy
                newRect.right = newRect.left + (newRect.bottom - newRect.top) * aspectW / aspectH
            }
            Corner.NONE -> return
        }
        val h = newRect.bottom - newRect.top
        val w = newRect.right - newRect.left
        if (h < minCropSize || w < minCropSize) {
            return
        }
        if (newRect.left < imageRect.left || newRect.top < imageRect.top ||
            newRect.right > imageRect.right || newRect.bottom > imageRect.bottom
        ) {
            return
        }
        cropRect.set(newRect)
    }

    private fun dp(value: Float): Float {
        return value * resources.displayMetrics.density
    }
}
