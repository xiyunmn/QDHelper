package com.xiyunmn.qdhelper.shared.compose

import com.xiyunmn.qdhelper.shared.ui.installModuleWatermark
import com.xiyunmn.qdhelper.shared.ui.allowDisplayCutoutCompat
import com.xiyunmn.qdhelper.shared.ui.setDecorFitsSystemWindowsCompat
import com.xiyunmn.qdhelper.shared.ui.disableSystemBarContrastCompat
import com.xiyunmn.qdhelper.shared.ui.copySystemBarsFrom
import com.xiyunmn.qdhelper.shared.ui.safeAreaCompat
import com.xiyunmn.qdhelper.shared.ui.consumeSafeAreaCompat

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.PathInterpolator
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Card and scrim share a surface and clock. WindowManager never animates a second dim layer. */
class ModuleFloatingWindow(context: Context, brightness: Float, source: View = context.hostActivity().window.decorView) :
    Dialog(context, R.style.QDHelperFloatingDialog) {
    var onDismissStarted: (() -> Unit)? = null
    private var animator: ValueAnimator? = null
    private var progress = 0f
    private var closing = false
    private var cancelable = true
    private var cancelOnOutside = true
    private var enterListener: ViewTreeObserver.OnPreDrawListener? = null
    private val layer = FloatingDialogLayer(context) { if (cancelable && cancelOnOutside) cancel() }

    init {
        window!!.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            val sourceAttributes = source.rootView.layoutParams as? WindowManager.LayoutParams
            if ((sourceAttributes?.flags ?: context.hostActivity().window.attributes.flags) and WindowManager.LayoutParams.FLAG_SECURE != 0) {
                addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            attributes = attributes.apply {
                dimAmount = 0f
                screenBrightness = brightness
                windowAnimations = 0
                softInputMode = if (android.os.Build.VERSION.SDK_INT >= 30)
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                else WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            }
            allowDisplayCutoutCompat()
            setDecorFitsSystemWindowsCompat(false)
            @Suppress("DEPRECATION")
            run { statusBarColor = Color.TRANSPARENT; navigationBarColor = Color.TRANSPARENT }
            disableSystemBarContrastCompat()
            copySystemBarsFrom(source)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setGravity(Gravity.CENTER)
        }
    }

    override fun setContentView(view: View) {
        layer.removeAllViews()
        layer.addView(view)
        view.installModuleWatermark()
        super.setContentView(layer)
    }
    override fun setCancelable(flag: Boolean) { cancelable = flag; super.setCancelable(flag) }
    override fun setCanceledOnTouchOutside(cancel: Boolean) {
        cancelOnOutside = cancel
        if (cancel) cancelable = true
        super.setCanceledOnTouchOutside(cancel)
    }
    override fun show() {
        if (isShowing) return
        stop()
        closing = false
        render(0f)
        // A cold Compose composition may take multiple frames. Do not consume the entrance
        // animation before its first measurement, or the first visible card jumps in midway.
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (layer.width == 0 || layer.height == 0 || layer.getChildAt(0)?.height == 0) return true
                clearEnterListener()
                if (!closing && isShowing) animate(1f)
                return true
            }
        }
        enterListener = listener
        layer.viewTreeObserver.addOnPreDrawListener(listener)
        try { super.show() } catch (error: Exception) { stop(); throw error }
    }
    override fun dismiss() {
        if (!isShowing) { stop(); super.dismiss(); return }
        if (closing) return
        closing = true
        onDismissStarted?.invoke()
        animate(0f)
    }
    fun dismissImmediately() { stop(); super.dismiss() }
    private fun stop() {
        clearEnterListener()
        animator?.removeAllListeners()
        animator?.cancel()
        animator = null
    }
    private fun clearEnterListener() {
        enterListener?.let { if (layer.viewTreeObserver.isAlive) layer.viewTreeObserver.removeOnPreDrawListener(it) }
        enterListener = null
    }
    private fun animate(target: Float) {
        stop()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            render(target)
            if (target == 0f) super.dismiss()
            return
        }
        animator = ValueAnimator.ofFloat(progress, target).apply {
            duration = if (target == 1f) 225L else 180L
            interpolator = PathInterpolator(.2f, 0f, 0f, 1f)
            addUpdateListener { render(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    animator = null
                    if (target == 0f) finishDismiss()
                }
            })
            start()
        }
    }
    private fun finishDismiss() { super.dismiss() }
    private fun render(value: Float) {
        progress = value
        layer.render(value)
    }
}

/** Full-window transparent host, with the same centered, bounded floating card as before. */
private class FloatingDialogLayer(context: Context, private val dismissOutside: () -> Unit) : ViewGroup(context) {
    private val density = resources.displayMetrics.density
    private var scrimAlpha = 0
    private var outsideGesture = false

    init {
        setWillNotDraw(false)
        clipChildren = false
        setOnApplyWindowInsetsListener { _, insets ->
            val safe = insets.safeAreaCompat()
            setPadding(safe.left, safe.top, safe.right, safe.bottom)
            // This layer already reserves the safe area; descendants must not apply it twice.
            insets.consumeSafeAreaCompat()
        }
    }
    fun render(progress: Float) {
        scrimAlpha = (255f * .22f * progress).toInt()
        getChildAt(0)?.apply {
            alpha = progress
            translationY = (1f - progress) * 8f * density
        }
        invalidate()
    }
    override fun onDraw(canvas: Canvas) { canvas.drawColor(Color.argb(scrimAlpha, 0, 0, 0)) }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val cardWidth = minOf((width - paddingLeft - paddingRight - 32 * density).toInt(), (520 * density).toInt()).coerceAtLeast(0)
        val cardHeight = (height - paddingTop - paddingBottom).coerceAtLeast(0)
        getChildAt(0)?.measure(MeasureSpec.makeMeasureSpec(cardWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(cardHeight, MeasureSpec.AT_MOST))
        setMeasuredDimension(width, height)
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        getChildAt(0)?.let { card ->
            val x = paddingLeft + (width - paddingLeft - paddingRight - card.measuredWidth) / 2
            val y = paddingTop + (height - paddingTop - paddingBottom - card.measuredHeight) / 2
            card.layout(x, y, x + card.measuredWidth, y + card.measuredHeight)
        }
    }
    override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    private fun outside(event: MotionEvent): Boolean = getChildAt(0)?.let {
        event.x < it.x || event.x >= it.x + it.width || event.y < it.y || event.y >= it.y + it.height
    } ?: true
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> outsideGesture = outside(event)
            MotionEvent.ACTION_UP -> { if (outsideGesture && outside(event)) performClick(); outsideGesture = false }
            MotionEvent.ACTION_CANCEL -> outsideGesture = false
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); dismissOutside(); return true }
}

fun moduleWindowBrightness(source: View, activity: Activity): Float =
    (source.rootView.layoutParams as? WindowManager.LayoutParams)?.screenBrightness?.takeIf { it in 0f..1f }
        ?: activity.window.attributes.screenBrightness.takeIf { it in 0f..1f }
        ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE

private fun Context.hostActivity(): Activity {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        val base = context.baseContext
        if (base === context) break
        context = base
    }
    error("A floating module dialog requires a host Activity")
}

/** Shared floating host for choices, confirmations and small utility popups. */
@Composable
fun ModuleFloatingDialog(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    val activity = LocalContext.current.hostActivity()
    val source = LocalView.current
    val parent = rememberCompositionContext()
    val parentOwner = LocalLifecycleOwner.current
    val latestDismiss = rememberUpdatedState(onDismissRequest)
    val latestContent = rememberUpdatedState(content)
    DisposableEffect(activity, parent, parentOwner) {
        val owner = ModuleCompositionOwner()
        val context = ModuleComposeContext.create(activity)
        val view = ComposeView(context)
        val dialog = ModuleFloatingWindow(context, moduleWindowBrightness(source, activity), source)
        var disposed = false
        var leavingComposition = false
        fun release() {
            if (disposed) return
            disposed = true
            owner.close()
            view.disposeComposition()
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> if (!disposed) owner.resume()
                Lifecycle.Event.ON_PAUSE -> if (!disposed) owner.pause()
                Lifecycle.Event.ON_DESTROY -> { dialog.dismissImmediately(); release() }
                else -> Unit
            }
        }
        dialog.setOnDismissListener {
            parentOwner.lifecycle.removeObserver(observer)
            release()
            if (!leavingComposition) latestDismiss.value()
        }
        try {
            owner.attach(view)
            view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            view.setParentCompositionContext(parent)
            dialog.setContentView(view)
            owner.attach(dialog.window!!.decorView)
            dialog.setCanceledOnTouchOutside(true)
            view.setContent { latestContent.value() }
            parentOwner.lifecycle.addObserver(observer)
            dialog.show()
            if (parentOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) owner.resume()
        } catch (error: Exception) {
            parentOwner.lifecycle.removeObserver(observer)
            dialog.dismissImmediately()
            release()
            throw error
        }
        onDispose {
            leavingComposition = true
            // Finish the exit with its composition intact. Lifecycle destruction bypasses it.
            if (activity.isDestroyed || activity.isFinishing) dialog.dismissImmediately() else dialog.dismiss()
        }
    }
}
