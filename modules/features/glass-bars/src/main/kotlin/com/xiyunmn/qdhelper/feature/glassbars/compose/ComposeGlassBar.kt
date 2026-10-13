package com.xiyunmn.qdhelper.feature.glassbars.compose

import android.animation.ValueAnimator
import android.content.Context
import android.view.MotionEvent
import android.widget.FrameLayout
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.xiyunmn.qdhelper.shared.compose.ModuleCompositionOwner
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.shared.ui.glass.GlassBarMotion
import com.xiyunmn.qdhelper.shared.ui.glass.GlassEffectsSpec
import com.xiyunmn.qdhelper.shared.ui.glass.GlassRenderSurface
import com.xiyunmn.qdhelper.shared.ui.glass.GlassTabContent
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.math.ceil

/** Two draw-only compositions, one explicit module recomposer and one motion frame loop. */
internal class ComposeGlassBar(
    context: Context,
    private val source: View,
    private val nativeTabs: View?,
    failure: (Throwable) -> Unit,
) : AutoCloseable {
    private val density = context.resources.displayMetrics.density
    val halo = ceil(64f * density).toInt()
    val motion = GlassBarMotion(density)
    private val surface = GlassRenderSurface(source, failure)
    private val owner = ModuleCompositionOwner()
    // Never borrow the host's window recomposer: its AndroidX classes/owners may be host-loaded.
    private val scope = CoroutineScope(SupervisorJob() + AndroidUiDispatcher.Main + CoroutineExceptionHandler { _, error -> failure(error) })
    private val recomposer = Recomposer(scope.coroutineContext)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var revision by mutableIntStateOf(0)
    private var width = 0
    private var height = 0
    private var effects = GlassEffectsSpec()
    private var night = false
    private var configured = false
    private var animationsEnabled = true
    private var active = false
    private var renderingActive = false
    private var visible = false
    private var closed = false
    private var ready = false
    private var tabContent: GlassTabContent? = null
    private var reportedState = -1
    private var reportedFrame = false
    private val lightChanged: () -> Unit = { revision++ }
    private val location = IntArray(2)
    val background = GlassComposeLayer(context, false)
    val foreground = nativeTabs?.let { GlassComposeLayer(context, true) }

    init {
        motion.onChanged = {
            surface.update(motion)
            revision++
            if (motion.running) wake.trySend(Unit)
        }
        scope.launch { recomposer.runRecomposeAndApplyChanges() }
    }

    fun configure(width: Int, height: Int, effects: GlassEffectsSpec, night: Boolean) {
        val animate = ValueAnimator.areAnimatorsEnabled()
        if (closed || configured && this.width == width && this.height == height && this.effects == effects && this.night == night && animationsEnabled == animate) return
        this.width = width
        this.height = height
        this.effects = effects
        this.night = night
        animationsEnabled = animate
        configured = true
        surface.configure(width, height, density, effects, night)
        motion.configure(width, height, effects.press, animate)
        updateActive()
    }

    fun setActive(value: Boolean) {
        active = value
        updateActive()
    }

    fun setTabContent(content: GlassTabContent?) {
        if (closed || tabContent == content) return
        tabContent = content
        surface.setTabContent(content)
        revision++
    }

    /** Called after native geometry/theme synchronization, on an existing host pre-draw. */
    fun prepare() {
        if (closed) return
        visible = background.isAttachedToWindow && background.isShown && background.windowVisibility == View.VISIBLE
        updateActive()
        // A loading/transitioning host page can hide the sampled source while keeping the bar
        // interactive. Freeze the backdrop then; don't disable native controls or their motion.
        if (!active || !visible || !source.isShown || !background.isHardwareAccelerated) return
        background.getLocationOnScreen(location)
        val nextReady = surface.prepare(location[0] + halo, location[1] + halo, nativeTabs)
        if (ready != nextReady) { ready = nextReady; revision++ }
    }

    private fun updateActive() {
        if (closed) return
        val enabled = active && visible
        if (enabled && !renderingActive) {
            surface.invalidateMaterials()
            // Resume need not change selection/geometry or start an animation. Explicitly
            // wake both draw-only compositions so their cached commands are regenerated.
            revision++
        }
        renderingActive = enabled
        val state = (if (active) 1 else 0) or (if (visible) 2 else 0) or
            (if (background.isAttachedToWindow) 4 else 0) or (if (background.isShown) 8 else 0) or
            (if (background.windowVisibility == View.VISIBLE) 16 else 0) or (if (source.isShown) 32 else 0)
        if (state != reportedState) {
            reportedState = state
            FeatureHooks.i("Glass Compose state: flags=$state, enabled=$enabled, window=${background.windowVisibility}")
        }
        motion.setActive(enabled)
        surface.setActive(enabled, lightChanged)
        if (enabled) owner.resume() else owner.pause()
    }

    private suspend fun runMotionFrames() {
        while (!closed) {
            wake.receive()
            var previous = 0L
            while (!closed && motion.running) {
                withFrameNanos { time ->
                    if (!reportedFrame) {
                        reportedFrame = true
                        FeatureHooks.i("Glass Compose motion: first frame")
                    }
                    val dt = if (previous == 0L) 1f / 60f else ((time - previous) / 1_000_000_000f).coerceIn(0f, 0.064f)
                    previous = time
                    motion.advance(dt)
                }
            }
        }
    }

    inner class GlassComposeLayer(context: Context, val front: Boolean) : FrameLayout(context) {
        private val compose = ComposeView(context)
        val halo: Int get() = this@ComposeGlassBar.halo
        init {
            isClickable = false
            isLongClickable = false
            isFocusable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            clipChildren = false
            clipToPadding = false
            owner.attach(compose)
            compose.setParentCompositionContext(recomposer)
            compose.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            compose.setContent { Content() }
            addView(compose, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }

        fun disposeComposition() = compose.disposeComposition()
        val hasComposition: Boolean get() = compose.hasComposition

        // Keep all touch and accessibility behavior in the original native controls.
        override fun dispatchTouchEvent(event: MotionEvent): Boolean = false

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(
                if (this@ComposeGlassBar.width > 0) MeasureSpec.makeMeasureSpec(this@ComposeGlassBar.width + 2 * halo, MeasureSpec.EXACTLY) else widthMeasureSpec,
                if (this@ComposeGlassBar.height > 0) MeasureSpec.makeMeasureSpec(this@ComposeGlassBar.height + 2 * halo, MeasureSpec.EXACTLY) else heightMeasureSpec,
            )
        }

        @Composable
        private fun Content() {
            // Qualify the controller: View.animate() otherwise wins Kotlin member resolution
            // inside this FrameLayout and silently leaves the motion frame loop unstarted.
            if (!front) LaunchedEffect(this@ComposeGlassBar) { this@ComposeGlassBar.runMotionFrames() }
            Box(Modifier.fillMaxSize().drawBehind {
                // Read high-frequency state here, never during composition or measurement.
                @Suppress("UNUSED_VARIABLE") val frame = revision
                if (!closed && width > 0 && height > 0) {
                    val canvas = drawContext.canvas.nativeCanvas
                    val save = canvas.save()
                    try {
                        canvas.translate(halo.toFloat(), halo.toFloat())
                        surface.draw(canvas, front)
                    } finally { canvas.restoreToCount(save) }
                }
            })
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        motion.close()
        background.disposeComposition()
        foreground?.disposeComposition()
        owner.close()
        recomposer.cancel()
        scope.cancel()
        wake.close()
        surface.close()
    }
}
