package com.xiyunmn.qdhelper.capability.originalimage

import com.xiyunmn.qdhelper.shared.ui.installModuleWatermark
import com.xiyunmn.qdhelper.shared.ui.safeAreaCompat
import com.xiyunmn.qdhelper.shared.ui.setDecorFitsSystemWindowsCompat

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.xiyunmn.qdhelper.shared.media.ImageGalleryStore
import com.xiyunmn.qdhelper.shared.media.OriginalImageData
import com.xiyunmn.qdhelper.shared.ui.applySystemBarsAppearance
import com.xiyunmn.qdhelper.shared.ui.dp
import com.xiyunmn.qdhelper.hook.concurrency.ManagedExecutor
import java.util.concurrent.atomic.AtomicBoolean

object OriginalImageDialog {
    private const val MAX_PREVIEW_DIMENSION = 4096
    private const val VIEWER_BACKGROUND = 0xFF0B0B0D.toInt()
    private const val TOOLBAR_BACKGROUND = 0xC20B0B0D.toInt()
    private const val ACCENT_COLOR = 0xFFE95555.toInt()
    private val worker = ManagedExecutor(
        ownerKey = "capability.original-image",
        threadName = "QDHelper-original-image",
        threadCount = 2,
    )
    private val mainHandler = Handler(Looper.getMainLooper())

    fun show(
        context: Context,
        title: String,
        fileStem: String,
        loader: () -> OriginalImageData?,
    ) {
        val dialog = Dialog(context, android.R.style.Theme_Material_NoActionBar)
        val root = FrameLayout(context).apply {
            setBackgroundColor(VIEWER_BACKGROUND)
            alpha = 0f
        }
        root.installModuleWatermark(darkSurface = true)
        val imageView = ZoomImageView(context).apply {
            contentDescription = title
            setBackgroundColor(VIEWER_BACKGROUND)
        }
        root.addView(imageView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        val progress = ProgressBar(context).apply {
            indeterminateTintList = ColorStateList.valueOf(ACCENT_COLOR)
        }
        root.addView(progress, centeredWrapParams())
        val status = TextView(context).apply {
            textSize = 15f
            setTextColor(0xFFF2F2F4.toInt())
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(context.dp(24), context.dp(16), context.dp(24), context.dp(16))
        }
        root.addView(status, centeredWrapParams())

        val toolbar = FrameLayout(context).apply {
            setBackgroundColor(TOOLBAR_BACKGROUND)
            setPadding(context.dp(4), 0, context.dp(4), 0)
        }
        val toolbarHeight = context.dp(52)
        root.addView(toolbar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            toolbarHeight,
            Gravity.TOP,
        ))
        toolbar.addView(TextView(context).apply {
            text = title
            textSize = 16f
            setTextColor(0xFFF6F6F7.toInt())
            gravity = Gravity.CENTER
            maxLines = 1
        }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ).apply {
            marginStart = context.dp(52)
            marginEnd = context.dp(52)
        })
        val closeButton = toolbarButton(
            context,
            ViewerIcon.Back,
            "关闭",
        ).apply { setOnClickListener { dialog.dismiss() } }
        toolbar.addView(closeButton, toolbarButtonParams(context, Gravity.START))
        val saveButton = toolbarButton(
            context,
            ViewerIcon.Download,
            "保存到相册",
        ).apply {
            isEnabled = false
            alpha = 0.42f
        }
        toolbar.addView(saveButton, toolbarButtonParams(context, Gravity.END))

        root.setOnApplyWindowInsetsListener { _, insets ->
            val topInset = insets.safeAreaCompat().top
            toolbar.setPadding(context.dp(4), topInset, context.dp(4), 0)
            (toolbar.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                val targetHeight = toolbarHeight + topInset
                if (params.height != targetHeight) {
                    params.height = targetHeight
                    toolbar.layoutParams = params
                }
            }
            insets
        }

        dialog.setContentView(root)
        val cancelled = AtomicBoolean(false)
        var imageData: OriginalImageData? = null
        var loading = false

        fun load() {
            if (loading) return
            loading = true
            progress.visibility = View.VISIBLE
            status.visibility = View.GONE
            val accepted = worker.execute {
                val loaded = runCatching { loader() }.getOrNull()
                val bitmap = loaded?.let(::decodePreviewBitmap)
                mainHandler.post {
                    loading = false
                    if (cancelled.get() || !dialog.isShowing) {
                        bitmap?.recycle()
                        return@post
                    }
                    progress.visibility = View.GONE
                    if (loaded == null || bitmap == null) {
                        status.text = "图片加载失败\n重试"
                        status.visibility = View.VISIBLE
                        status.setOnClickListener { load() }
                    } else {
                        imageData = loaded
                        status.visibility = View.GONE
                        status.setOnClickListener(null)
                        imageView.setPreviewBitmap(bitmap)
                        saveButton.isEnabled = true
                        saveButton.alpha = 1f
                    }
                }
            }
            if (!accepted) {
                loading = false
                progress.visibility = View.GONE
                status.text = "图片加载已停止，请重新打开"
                status.visibility = View.VISIBLE
            }
        }

        saveButton.setOnClickListener {
            val data = imageData ?: return@setOnClickListener
            saveButton.isEnabled = false
            saveButton.alpha = 0.42f
            val accepted = worker.execute {
                val saved = ImageGalleryStore.save(context.applicationContext ?: context, data, fileStem)
                mainHandler.post {
                    if (!cancelled.get() && dialog.isShowing) {
                        saveButton.isEnabled = true
                        saveButton.alpha = 1f
                        Toast.makeText(
                            context,
                            if (saved) "已保存到相册" else "保存失败",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
            if (!accepted) {
                saveButton.isEnabled = true
                saveButton.alpha = 1f
            }
        }

        dialog.setOnDismissListener {
            cancelled.set(true)
            imageView.release()
        }
        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(VIEWER_BACKGROUND))
                setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                statusBarColor = Color.TRANSPARENT
                navigationBarColor = VIEWER_BACKGROUND
                setDecorFitsSystemWindowsCompat(false)
                applySystemBarsAppearance(light = false)
            }
            root.requestApplyInsets()
            root.animate().alpha(1f).setDuration(160L).start()
            load()
        }
        dialog.show()
    }

    private fun decodePreviewBitmap(data: OriginalImageData): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data.bytes, 0, data.bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > MAX_PREVIEW_DIMENSION ||
            bounds.outHeight / sampleSize > MAX_PREVIEW_DIMENSION
        ) {
            sampleSize *= 2
        }
        return BitmapFactory.decodeByteArray(
            data.bytes,
            0,
            data.bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        )
    }

    private fun toolbarButton(context: Context, icon: ViewerIcon, description: String): ImageButton {
        return ImageButton(context).apply {
            setImageDrawable(ViewerIconDrawable(icon, context.dpFloat(2f)))
            contentDescription = description
            tooltipText = description
            val ripple = TypedValue()
            if (context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)) {
                setBackgroundResource(ripple.resourceId)
            } else {
                background = null
            }
            setPadding(context.dp(13), context.dp(13), context.dp(13), context.dp(13))
        }
    }

    private fun toolbarButtonParams(context: Context, gravity: Int): FrameLayout.LayoutParams {
        return FrameLayout.LayoutParams(context.dp(52), context.dp(52), gravity)
    }

    private fun centeredWrapParams(): FrameLayout.LayoutParams {
        return FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        )
    }

    private fun Context.dpFloat(value: Float): Float = value * resources.displayMetrics.density

    private enum class ViewerIcon {
        Back,
        Download,
    }

    private class ViewerIconDrawable(
        private val icon: ViewerIcon,
        strokeWidth: Float,
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF7F7F8.toInt()
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        override fun draw(canvas: Canvas) {
            val left = bounds.left.toFloat()
            val top = bounds.top.toFloat()
            val width = bounds.width().toFloat()
            val height = bounds.height().toFloat()
            fun x(fraction: Float) = left + width * fraction
            fun y(fraction: Float) = top + height * fraction
            when (icon) {
                ViewerIcon.Back -> {
                    canvas.drawLine(x(0.68f), y(0.18f), x(0.35f), y(0.5f), paint)
                    canvas.drawLine(x(0.35f), y(0.5f), x(0.68f), y(0.82f), paint)
                }
                ViewerIcon.Download -> {
                    canvas.drawLine(x(0.5f), y(0.14f), x(0.5f), y(0.62f), paint)
                    canvas.drawLine(x(0.28f), y(0.43f), x(0.5f), y(0.65f), paint)
                    canvas.drawLine(x(0.5f), y(0.65f), x(0.72f), y(0.43f), paint)
                    canvas.drawLine(x(0.2f), y(0.82f), x(0.8f), y(0.82f), paint)
                }
            }
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
