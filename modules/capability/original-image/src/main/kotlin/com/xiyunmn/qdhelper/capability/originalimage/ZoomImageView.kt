package com.xiyunmn.qdhelper.capability.originalimage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView
import kotlin.math.min

internal class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ImageView(context, attrs) {
    private val transform = Matrix()
    private var previewBitmap: Bitmap? = null
    private var minimumScale = 1f
    private var currentScale = 1f

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean = previewBitmap != null

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val nextScale = (currentScale * detector.scaleFactor)
                    .coerceIn(minimumScale, minimumScale * MAX_SCALE_MULTIPLIER)
                val factor = nextScale / currentScale
                transform.postScale(factor, factor, detector.focusX, detector.focusY)
                currentScale = nextScale
                constrainTransform()
                return true
            }
        },
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = previewBitmap != null

            override fun onScroll(
                first: MotionEvent?,
                current: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                if (previewBitmap == null || scaleDetector.isInProgress) return false
                transform.postTranslate(-distanceX, -distanceY)
                constrainTransform()
                return true
            }

            override fun onDoubleTap(event: MotionEvent): Boolean {
                if (previewBitmap == null) return false
                val target = if (currentScale > minimumScale * 1.15f) {
                    minimumScale
                } else {
                    minimumScale * DOUBLE_TAP_SCALE_MULTIPLIER
                }
                val factor = target / currentScale
                transform.postScale(factor, factor, event.x, event.y)
                currentScale = target
                constrainTransform()
                return true
            }
        },
    )

    init {
        scaleType = ScaleType.MATRIX
    }

    fun setPreviewBitmap(bitmap: Bitmap) {
        previewBitmap?.takeIf { it !== bitmap }?.recycle()
        previewBitmap = bitmap
        setImageBitmap(bitmap)
        resetTransform()
    }

    fun release() {
        setImageDrawable(null)
        previewBitmap?.recycle()
        previewBitmap = null
        transform.reset()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width != oldWidth || height != oldHeight) resetTransform()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (previewBitmap == null) return false
        parent?.requestDisallowInterceptTouchEvent(true)
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            constrainTransform()
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun resetTransform() {
        val bitmap = previewBitmap ?: return
        if (width <= 0 || height <= 0 || bitmap.width <= 0 || bitmap.height <= 0) return
        minimumScale = min(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        currentScale = minimumScale
        val left = (width - bitmap.width * minimumScale) / 2f
        val top = (height - bitmap.height * minimumScale) / 2f
        transform.setScale(minimumScale, minimumScale)
        transform.postTranslate(left, top)
        imageMatrix = transform
    }

    private fun constrainTransform() {
        val bitmap = previewBitmap ?: return
        val bounds = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        transform.mapRect(bounds)
        var offsetX = 0f
        var offsetY = 0f
        offsetX = if (bounds.width() <= width) {
            width / 2f - bounds.centerX()
        } else {
            when {
                bounds.left > 0f -> -bounds.left
                bounds.right < width -> width - bounds.right
                else -> 0f
            }
        }
        offsetY = if (bounds.height() <= height) {
            height / 2f - bounds.centerY()
        } else {
            when {
                bounds.top > 0f -> -bounds.top
                bounds.bottom < height -> height - bounds.bottom
                else -> 0f
            }
        }
        transform.postTranslate(offsetX, offsetY)
        imageMatrix = transform
    }

    private companion object {
        const val MAX_SCALE_MULTIPLIER = 6f
        const val DOUBLE_TAP_SCALE_MULTIPLIER = 2.5f
    }
}
