package com.xiyunmn.qdhelper.feature.glassbars

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Region
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.TouchDelegate
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.xiyunmn.qdhelper.shared.ui.UiColors
import com.xiyunmn.qdhelper.shared.ui.setPressedAlpha
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
    private val shelfStateIcon = ShelfStateIcon()
    private var animationsEnabled = true
    private var shelfAnimator: ValueAnimator? = null

    init {
        shelf.icon?.setImageDrawable(shelfStateIcon)
        bar.addView(shelf.view, LinearLayout.LayoutParams(dp(90), ViewGroup.LayoutParams.MATCH_PARENT))
        audio.view.setPadding(dp(4), 0, dp(4), 0)
        bar.addView(audio.view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { marginStart = dp(4) })
        val readSlot = FrameLayout(context)
        read.view.minimumWidth = dp(100)
        read.view.minimumHeight = dp(40)
        read.view.setPadding(dp(18), 0, dp(18), 0)
        readSlot.addView(read.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40), Gravity.END or Gravity.CENTER_VERTICAL))
        bar.addView(readSlot, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = dp(12) })
        val updateTargets = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateTouchTargets() }
        bar.addOnLayoutChangeListener(updateTargets)
        audio.view.addOnLayoutChangeListener(updateTargets)
        read.view.addOnLayoutChangeListener(updateTargets)
        bar.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { updateTouchTargets() }
            override fun onViewDetachedFromWindow(view: View) {
                bar.touchDelegate = null
                finishShelfAnimation()
            }
        })
        applyTheme()
    }

    fun setJoined(value: Boolean) {
        if (joined == value) return
        val wasBound = joined != null
        joined = value
        shelfAnimator?.cancel()
        shelfAnimator = null
        val target = if (value) 1f else 0f
        if (!wasBound || !animationsEnabled || !ValueAnimator.areAnimatorsEnabled() || !bar.isAttachedToWindow) {
            shelfStateIcon.progress = target
            return
        }
        shelfAnimator = ValueAnimator.ofFloat(shelfStateIcon.progress, target).apply {
            duration = 180L
            addUpdateListener { shelfStateIcon.progress = it.animatedValue as Float }
            start()
        }
    }

    fun setAnimationsEnabled(enabled: Boolean) {
        animationsEnabled = enabled
        if (!enabled) finishShelfAnimation()
    }

    private fun finishShelfAnimation() {
        shelfAnimator?.cancel()
        shelfAnimator = null
        shelfStateIcon.progress = if (joined == true) 1f else 0f
    }

    private fun updateTouchTargets() {
        if (!bar.isAttachedToWindow || bar.width == 0 || bar.touchDelegate != null) return
        // This module owns the bar; only the compact buttons receive expanded hit areas.
        bar.touchDelegate = object : TouchDelegate(Rect(), bar) {
            private var active: ExpandedTouchTarget? = null
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    active = createTouchTargets().firstOrNull { it.bounds.contains(event.x.toInt(), event.y.toInt()) }
                }
                val target = active ?: return false
                if (!target.view.isShown || !target.view.isEnabled) {
                    val cancel = MotionEvent.obtain(event)
                    cancel.action = MotionEvent.ACTION_CANCEL
                    target.delegate.onTouchEvent(cancel)
                    cancel.recycle()
                    active = null
                    return true
                }
                val handled = target.delegate.onTouchEvent(event)
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) active = null
                return handled
            }

            override fun getTouchDelegateInfo(): AccessibilityNodeInfo.TouchDelegateInfo {
                val targets = createTouchTargets()
                if (targets.isEmpty()) return super.getTouchDelegateInfo()
                return AccessibilityNodeInfo.TouchDelegateInfo(targets.associate { Region(it.bounds) to it.view })
            }
        }
    }

    private class ExpandedTouchTarget(val bounds: Rect, val view: View) {
        val delegate = TouchDelegate(bounds, view)
    }

    private fun createTouchTargets(): List<ExpandedTouchTarget> =
        listOf(audio.view, read.view).filter { it.isShown && it.isEnabled }.mapNotNull { target ->
            val bounds = Rect()
            target.getDrawingRect(bounds)
            bar.offsetDescendantRectToMyCoords(target, bounds)
            // Keep each target inside its horizontal slot, including at narrow bar widths.
            val slot = Rect(bounds)
            if (target === read.view) {
                val parent = target.parent as View
                parent.getDrawingRect(slot)
                bar.offsetDescendantRectToMyCoords(parent, slot)
            }
            val extraWidth = (dp(48) - bounds.width()).coerceAtLeast(0)
            val extraHeight = (dp(48) - bounds.height()).coerceAtLeast(0)
            bounds.left = (bounds.left - extraWidth / 2).coerceAtLeast(slot.left)
            bounds.right = (bounds.right + extraWidth - extraWidth / 2).coerceAtMost(slot.right)
            bounds.top -= extraHeight / 2
            bounds.bottom += extraHeight - extraHeight / 2
            if (bounds.intersect(0, 0, bar.width, bar.height) && !bounds.isEmpty) ExpandedTouchTarget(bounds, target) else null
        }

    fun applyTheme() {
        UiColors.refresh(context)
        val next = Triple(UiColors.IsNight, UiColors.Text, UiColors.Brand)
        if (theme == next) return
        theme = next
        listOf(shelf, audio).forEach { button ->
            button.text.setTextColor(UiColors.Text)
            button.icon?.setColorFilter(UiColors.Text)
            button.view.background = null
        }
        read.text.setTextColor(Color.WHITE)
        read.view.background = GradientDrawable().apply {
            setColor(UiColors.Brand)
            cornerRadius = dp(20).toFloat()
        }
    }

    private fun button(kind: IconKind?, label: String): Button {
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isBaselineAligned = false
            isClickable = true
            isFocusable = true
            setPressedAlpha()
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

    private enum class IconKind { ADD, CHECK, AUDIO }

    private class ShelfStateIcon : Drawable() {
        private val add = ControlIcon(IconKind.ADD)
        private val check = ControlIcon(IconKind.CHECK)
        private var drawableAlpha = 255
        private var themeColorFilter: ColorFilter? = null
        var progress = 0f
            set(value) { field = value; invalidateSelf() }

        override fun onBoundsChange(bounds: Rect) {
            add.bounds = bounds
            check.bounds = bounds
        }
        override fun draw(canvas: Canvas) {
            add.alpha = (drawableAlpha * (1f - progress)).roundToInt()
            check.alpha = (drawableAlpha * progress).roundToInt()
            add.draw(canvas)
            check.draw(canvas)
        }
        override fun setAlpha(alpha: Int) { drawableAlpha = alpha; invalidateSelf() }
        override fun setColorFilter(colorFilter: ColorFilter?) {
            themeColorFilter = colorFilter
            add.colorFilter = themeColorFilter
            check.colorFilter = themeColorFilter
            invalidateSelf()
        }
        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

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
