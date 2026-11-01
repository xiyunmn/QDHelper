package com.xiyunmn.qdhelper.feature.glassbars

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.xiyunmn.qdhelper.shared.ui.UiColors
import kotlin.math.roundToInt

/** Module-owned controls with the same compact proportions as the original detail bar. */
internal class DetailGlassControls(private val context: Context) {
    class Button(val view: LinearLayout, val text: TextView, val icon: ImageView?)
    private val density = context.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).roundToInt()
    val bar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isBaselineAligned = false
        setPadding(dp(16), 0, dp(16), 0)
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64), Gravity.BOTTOM)
    }
    val shelf = button(IconKind.ADD, "加入书架")
    val audio = button(IconKind.AUDIO, "听书")
    val read = button(null, "立即阅读")
    private var theme: Triple<Boolean, Int, Int>? = null
    private var joined: Boolean? = null

    init {
        bar.addView(shelf.view, LinearLayout.LayoutParams(dp(90), ViewGroup.LayoutParams.MATCH_PARENT))
        audio.view.setPadding(dp(4), 0, dp(4), 0)
        bar.addView(audio.view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { marginStart = dp(4) })
        val readSlot = FrameLayout(context)
        read.view.minimumWidth = dp(100)
        read.view.minimumHeight = dp(40)
        read.view.setPadding(dp(18), 0, dp(18), 0)
        readSlot.addView(read.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40), Gravity.END or Gravity.CENTER_VERTICAL))
        bar.addView(readSlot, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = dp(12) })
        applyTheme()
    }

    fun setJoined(value: Boolean) {
        if (joined == value) return
        joined = value
        shelf.icon?.setImageDrawable(ControlIcon(if (value) IconKind.CHECK else IconKind.ADD))
    }

    fun applyTheme() {
        UiColors.refresh(context)
        val next = Triple(UiColors.IsNight, UiColors.Text, UiColors.Brand)
        if (theme == next) return
        theme = next
        listOf(shelf, audio).forEach { button ->
            button.text.setTextColor(UiColors.Text)
            button.icon?.setColorFilter(UiColors.Text)
            button.view.background = ripple(Color.argb(if (UiColors.IsNight) 36 else 20, 128, 128, 128), null)
        }
        read.text.setTextColor(Color.WHITE)
        read.view.background = ripple(0x30FFFFFF, GradientDrawable().apply {
            setColor(UiColors.Brand)
            cornerRadius = dp(20).toFloat()
        })
    }

    private fun button(kind: IconKind?, label: String): Button {
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isBaselineAligned = false
            isClickable = true
            isFocusable = true
        }
        val icon = kind?.let {
            ImageView(context).apply {
                importantForAccessibility = ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO
                setImageDrawable(ControlIcon(it))
                val size = dp(if (kind == IconKind.AUDIO) 24 else 20)
                view.addView(this, LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(2) })
            }
        }
        val text = TextView(context).apply {
            this.text = label
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setSingleLine()
        }
        view.addView(text, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return Button(view, text, icon)
    }

    private fun ripple(color: Int, content: Drawable?) = RippleDrawable(ColorStateList.valueOf(color), content,
        GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(20).toFloat() })

    private enum class IconKind { ADD, CHECK, AUDIO }

    private class ControlIcon(private val kind: IconKind) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 1.8f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val path = Path().apply {
            when (kind) {
                IconKind.ADD -> {
                    moveTo(4f, 5f); lineTo(10f, 5f); quadTo(12f, 5f, 12f, 7f)
                    moveTo(4f, 5f); lineTo(4f, 19f); lineTo(10f, 19f); quadTo(12f, 19f, 12f, 21f)
                    moveTo(12f, 7f); lineTo(12f, 21f); quadTo(12f, 19f, 14f, 19f); lineTo(20f, 19f); lineTo(20f, 13f)
                    moveTo(14f, 7f); lineTo(22f, 7f); moveTo(18f, 3f); lineTo(18f, 11f)
                }
                IconKind.CHECK -> { moveTo(5f, 12f); lineTo(10f, 17f); lineTo(20f, 7f) }
                IconKind.AUDIO -> {
                    moveTo(4f, 14f); lineTo(4f, 11f); cubicTo(4f, 1f, 20f, 1f, 20f, 11f); lineTo(20f, 14f)
                    addRoundRect(3f, 12f, 7f, 21f, 2f, 2f, Path.Direction.CW)
                    addRoundRect(17f, 12f, 21f, 21f, 2f, 2f, Path.Direction.CW)
                }
            }
        }
        override fun draw(canvas: Canvas) {
            val save = canvas.save()
            canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
            canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
            canvas.drawPath(path, paint)
            canvas.restoreToCount(save)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
