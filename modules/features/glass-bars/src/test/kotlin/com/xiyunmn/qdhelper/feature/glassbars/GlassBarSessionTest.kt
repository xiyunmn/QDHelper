package com.xiyunmn.qdhelper.feature.glassbars

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.ScrollView
import android.widget.TextView
import com.xiyunmn.qdhelper.feature.glassbars.compose.ComposeGlassBar.GlassComposeLayer
import com.xiyunmn.qdhelper.shared.compose.ModuleComposeEnvironment
import org.junit.Before
import com.xiyunmn.qdhelper.config.GlassBarDimensions
import com.xiyunmn.qdhelper.shared.ui.glass.GlassEffectsSpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Framework layout regression fixture; does not claim to validate a device GPU or a Dart page. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp-mdpi")
class GlassBarSessionTest {
    class NativeTabs(context: Context) : FrameLayout(context) {
        var selected = 0
        fun getCurrentItem() = selected
    }

    private val activityController = Robolectric.buildActivity(Activity::class.java).apply {
        get().setTheme(android.R.style.Theme_Material_Light_NoActionBar_Fullscreen)
    }.setup()
    private val activity = activityController.get()
    private val parent = RelativeLayout(activity)
    private val source = FrameLayout(activity)
    private val scroll = ScrollView(activity)
    private val bar = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; id = View.generateViewId() }
    private val actions = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
    private val button = Button(activity).apply { text = "Read" }
    private val flutter = FlutterGlassBridge {}
    private var session: GlassBarSession? = null
    private val effects = GlassEffectsSpec(false, false, false, false, false, false)
    private var parentTop = 0

    @Before
    fun configureModuleResources() {
        ModuleComposeEnvironment.configure(activity.applicationInfo)
    }

    private fun create(kind: GlassBarKind = GlassBarKind.MAIN, height: Int = 54, nativeTabs: Boolean = false) {
        scroll.setPadding(0, 0, 0, 7)
        scroll.addView(View(activity), ViewGroup.LayoutParams(400, 1800))
        source.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        parent.addView(source, RelativeLayout.LayoutParams(-1, -1).apply {
            if (kind == GlassBarKind.DETAIL) bottomMargin = height else addRule(RelativeLayout.ABOVE, bar.id)
        })
        val tabs = if (nativeTabs) NativeTabs(activity).also { strip ->
            strip.addView(actions, FrameLayout.LayoutParams(-1, height))
            bar.addView(strip, LinearLayout.LayoutParams(-1, height))
        } else {
            bar.addView(actions, LinearLayout.LayoutParams(-1, height))
            null
        }
        actions.addView(button, LinearLayout.LayoutParams(80, 40))
        parent.addView(bar, RelativeLayout.LayoutParams(-1, -2).apply { addRule(RelativeLayout.ALIGN_PARENT_BOTTOM) })
        activity.setContentView(parent)
        settle()
        val binding = GlassBarBinding(kind, parent, bar, source, nativeScroll = scroll.takeUnless { kind == GlassBarKind.MAIN },
            actionRow = actions.takeUnless { kind == GlassBarKind.MAIN }, tabs = tabs)
        session = GlassBarSession(binding, flutter) { error(it) }.also {
            it.attach(effects, true, GlassBarDimensions(heightDp = height, bottomGapDp = 12))
        }
        settle()
    }

    private fun settle(times: Int = 5) {
        repeat(times) {
            activity.window.decorView.apply {
                measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
                layout(0, 0, 400, 800)
            }
            parent.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            parent.layout(0, parentTop, 400, parentTop + 800)
            parent.viewTreeObserver.dispatchOnGlobalLayout()
        }
    }

    @After
    fun tearDown() {
        session?.close()
        flutter.close()
        activityController.pause().stop().destroy()
        // Drain Compose's pending dispatcher/frame callbacks before Robolectric resets its
        // looper between methods. Otherwise the cached AndroidUiDispatcher keeps a posted
        // flag for a callback that the test runner removed, starving the following test.
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(64))
    }

    private fun activateComposition() {
        activityController.visible().windowFocusChanged(true)
        // Robolectric's manually measured fixture leaves AttachInfo window visibility GONE.
        // Establish a genuinely visible host before testing the real composition/frame loop.
        val attach = org.robolectric.util.ReflectionHelpers.getField<Any>(parent, "mAttachInfo")
        org.robolectric.util.ReflectionHelpers.setField(attach, "mWindowVisibility", View.VISIBLE)
        session!!.update(GlassEffectsSpec(tilt = false), GlassBarDimensions(heightDp = 64, bottomGapDp = 12))
        settle()
        parent.viewTreeObserver.dispatchOnPreDraw()
    }

    private fun motion() = org.robolectric.util.ReflectionHelpers
        .getField<com.xiyunmn.qdhelper.feature.glassbars.compose.ComposeGlassBar>(session, "glass").motion

    @Test
    fun resumeInvalidatesStationaryMaterialOnceWithoutStartingContinuousFrames() {
        create(height = 64, nativeTabs = true)
        activateComposition()
        val glass = org.robolectric.util.ReflectionHelpers.getField<com.xiyunmn.qdhelper.feature.glassbars.compose.ComposeGlassBar>(session, "glass")
        glass.setActive(false)
        val paused = org.robolectric.util.ReflectionHelpers.callInstanceMethod<Int>(glass, "getRevision")
        glass.setActive(true)
        val resumed = org.robolectric.util.ReflectionHelpers.callInstanceMethod<Int>(glass, "getRevision")
        assertTrue("A stationary bar still needs its Compose draw cache invalidated on resume", resumed > paused)
        repeat(3) { glass.setActive(true) }
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(250))
        assertEquals(resumed, org.robolectric.util.ReflectionHelpers.callInstanceMethod<Int>(glass, "getRevision"))
        assertFalse(glass.motion.running)
    }

    @Test
    fun composedDragPreviewsImmediatelyAndCommitsOnlyOnRelease() {
        create(height = 64, nativeTabs = true)
        val strip = actions.parent as NativeTabs
        val second = Button(activity).apply { text = "Second" }
        actions.addView(second, LinearLayout.LayoutParams(80, 40))
        var clicks = 0
        second.setOnClickListener { clicks++; strip.selected = 1 }
        activateComposition()
        fun send(action: Int, target: View, time: Long) {
            val location = IntArray(2)
            target.getLocationOnScreen(location)
            session!!.observeTouch(action, location[0] + target.width / 2f, location[1] + target.height / 2f, time)
        }
        send(android.view.MotionEvent.ACTION_DOWN, button, 100)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(250))
        assertTrue(motion().progress > .5f)
        assertEquals(1f, button.scaleX, 0f)
        send(android.view.MotionEvent.ACTION_MOVE, second, 110)
        assertEquals(0, strip.selected)
        assertEquals(0, clicks)
        assertEquals(1f, second.scaleX, 0f)
        send(android.view.MotionEvent.ACTION_MOVE, button, 112)
        assertTrue(motion().progress > .5f)
        assertEquals(1f, button.scaleX, 0f)
        assertEquals(1f, second.scaleX, 0.001f)
        send(android.view.MotionEvent.ACTION_UP, second, 114)
        assertEquals(1, strip.selected)
        assertEquals(1, clicks)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
        assertEquals(1f, second.scaleX, 0.001f)
    }

    @Test
    fun composedNativeClickRunsTransitionAndDisposesComposition() {
        create(height = 64, nativeTabs = true)
        val strip = actions.parent as NativeTabs
        var peakProgress = 0f
        val second = Button(activity).apply { text = "Second"; setOnClickListener { strip.selected = 1 } }
        actions.addView(second, LinearLayout.LayoutParams(80, 40))
        activateComposition()
        val motion = motion()
        val update = motion.onChanged
        motion.onChanged = { peakProgress = maxOf(peakProgress, motion.progress); update?.invoke() }
        second.performClick()
        parent.viewTreeObserver.dispatchOnPreDraw()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
        assertTrue("The captured accent row must advance intermediate press frames", peakProgress > .5f)
        assertEquals(1f, second.scaleX, 0.001f)
        val layer = (0 until parent.childCount).map(parent::getChildAt).filterIsInstance<GlassComposeLayer>().first()
        session!!.close()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(64))
        assertFalse(layer.hasComposition)
    }

    @Test
    fun composedBarActuallyAdvancesPressFramesAfterNativeTouch() {
        create(height = 64, nativeTabs = true)
        activateComposition()
        val location = IntArray(2)
        button.getLocationOnScreen(location)
        session!!.observeTouch(android.view.MotionEvent.ACTION_DOWN,
            location[0] + button.width / 2f, location[1] + button.height / 2f, 100)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300))
        assertTrue("Compose clock must advance captured-content press scaling", motion().progress > .5f)
        assertEquals(1f, button.scaleX, 0f)
        session!!.observeTouch(android.view.MotionEvent.ACTION_UP,
            location[0] + button.width / 2f, location[1] + button.height / 2f, 400)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
        assertEquals(1f, button.scaleX, 0.001f)

        // A temporarily hidden sampled page must not deactivate a still-visible native bar.
        source.visibility = View.INVISIBLE
        parent.viewTreeObserver.dispatchOnPreDraw()
        session!!.observeTouch(android.view.MotionEvent.ACTION_DOWN,
            location[0] + button.width / 2f, location[1] + button.height / 2f, 500)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300))
        assertTrue(motion().progress > .5f)
        assertEquals(1f, button.scaleX, 0f)
        session!!.observeTouch(android.view.MotionEvent.ACTION_UP,
            location[0] + button.width / 2f, location[1] + button.height / 2f, 800)
    }

    @Test
    fun extendsContentPreservesControlsAndRestoresLayoutWithoutAccumulation() {
        create()
        var clicks = 0
        button.setOnClickListener { clicks++ }
        val layer = (0 until parent.childCount).map(parent::getChildAt).filterIsInstance<GlassComposeLayer>().single { !it.front }
        assertEquals(parent.indexOfChild(bar) - 1, parent.indexOfChild(layer))
        assertFalse(layer.isClickable)
        assertFalse(layer.isFocusable)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, layer.importantForAccessibility)
        assertSame(actions, button.parent)
        assertEquals(800, source.height)
        assertEquals(54, bar.height)
        assertEquals(16, (bar.layoutParams as RelativeLayout.LayoutParams).leftMargin)
        assertEquals(12, (bar.layoutParams as RelativeLayout.LayoutParams).bottomMargin)
        assertEquals(7 + 54 + 12, scroll.paddingBottom)
        assertFalse(scroll.clipToPadding)
        settle(20)
        assertEquals(7 + 54 + 12, scroll.paddingBottom)
        button.performClick()
        assertEquals(1, clicks)

        session?.close()
        settle()
        assertEquals(2, parent.childCount)
        assertEquals(bar.id, (source.layoutParams as RelativeLayout.LayoutParams).getRule(RelativeLayout.ABOVE))
        assertEquals(7, scroll.paddingBottom)
        assertTrue(scroll.clipToPadding)
        assertEquals(0, (bar.layoutParams as RelativeLayout.LayoutParams).bottomMargin)
        button.performClick()
        assertEquals(2, clicks)
    }

    @Test
    fun foregroundLensSharesBarGeometryPreservesNativeInputAndClosesWithItsLease() {
        create(height = 64, nativeTabs = true)
        val backdrop = (0 until parent.childCount).map(parent::getChildAt).filterIsInstance<GlassComposeLayer>().single { !it.front }
        val overlay = parent.getChildAt(parent.indexOfChild(bar) + 1)
        assertEquals(4, parent.childCount)
        assertFalse(overlay.isClickable)
        assertFalse(overlay.isLongClickable)
        assertFalse(overlay.isFocusable)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, overlay.importantForAccessibility)
        assertSame(requireNotNull(session).binding.tabs, actions.parent)
        assertSame(actions, button.parent)
        var clicks = 0
        button.setOnClickListener { clicks++ }
        button.performClick()
        assertEquals(1, clicks)

        bar.translationX = 7f
        bar.translationY = -9f
        bar.elevation = 6f
        bar.translationZ = 2f
        bar.alpha = 0.75f
        session?.update(effects, GlassBarDimensions(80, 75, 20))
        settle(12)
        parent.viewTreeObserver.dispatchOnPreDraw()
        for (layer in listOf(backdrop, overlay)) {
            assertEquals(bar.x - backdrop.halo, layer.x, 0.01f)
            assertEquals(bar.y - backdrop.halo, layer.y, 0.01f)
            assertEquals(bar.width + 2 * backdrop.halo, layer.width)
            assertEquals(bar.height + 2 * backdrop.halo, layer.height)
            assertEquals(bar.z, layer.z, 0f)
            assertEquals(bar.alpha, layer.alpha, 0f)
        }

        val replacement = GlassBarSession(requireNotNull(session).binding, flutter) { error(it) }
        replacement.attach(effects, true, GlassBarDimensions(heightDp = 64, bottomGapDp = 12))
        assertTrue(requireNotNull(session).closed)
        assertEquals(null, overlay.parent)
        assertEquals(4, parent.childCount)
        session = replacement
        replacement.close()
        assertFalse(backdrop.hasComposition)
        assertFalse((overlay as GlassComposeLayer).hasComposition)
        assertEquals(2, parent.childCount)
        button.performClick()
        assertEquals(2, clicks)
    }

    @Test
    fun hostThemePaddingAndDisabledStateSurviveDisable() {
        create()
        val nightBackground = ColorDrawable(Color.DKGRAY)
        bar.background = nightBackground
        scroll.setPadding(0, 0, 0, 20)
        button.isEnabled = false
        settle()
        assertEquals(20 + 54 + 12, scroll.paddingBottom)
        session?.close()
        assertSame(nightBackground, bar.background)
        assertEquals(20, scroll.paddingBottom)
        assertFalse(button.isEnabled)
    }

    @Test
    fun directoryFollowsDiscountHeightAndKeepsDynamicExportButton() {
        create(GlassBarKind.DIRECTORY, 72)
        val discount = View(activity)
        bar.addView(discount, 0, LinearLayout.LayoutParams(-1, 48))
        val export = Button(activity).apply { text = "Export" }
        actions.addView(export, 0, LinearLayout.LayoutParams(92, 40))
        settle()
        assertEquals(120, bar.height)
        assertEquals(7 + 120 + 12, scroll.paddingBottom)
        assertSame(actions, export.parent)
        discount.visibility = View.GONE
        settle()
        assertEquals(7 + 72 + 12, scroll.paddingBottom)
        session?.close()
        assertSame(actions, export.parent)
        assertSame(actions, button.parent)
        assertEquals(0, actions.indexOfChild(export))
    }

    @Test
    fun detailTransfersItsReservedMarginIntoTheScrollableContent() {
        create(GlassBarKind.DETAIL, 64)
        assertEquals(0, (source.layoutParams as RelativeLayout.LayoutParams).bottomMargin)
        assertEquals(800, source.height)
        assertEquals(7 + 64 + 12, scroll.paddingBottom)
        session?.close()
        assertEquals(64, (source.layoutParams as RelativeLayout.LayoutParams).bottomMargin)
        assertEquals(7, scroll.paddingBottom)
    }

    @Test
    fun hiddenBarsStopReservingScrollSpace() {
        create()
        bar.visibility = View.GONE
        settle()
        assertEquals(7, scroll.paddingBottom)
        bar.visibility = View.VISIBLE
        settle()
        assertEquals(7 + 54 + 12, scroll.paddingBottom)
    }

    @Test
    fun replacingALeaseReleasesTheOldSessionBeforeCapturingNewOriginalValues() {
        create()
        val old = requireNotNull(session)
        val replacement = GlassBarSession(old.binding, flutter) { error(it) }
        replacement.attach(effects, true, GlassBarDimensions(heightDp = 54, bottomGapDp = 12))
        session = replacement
        settle()
        assertTrue(old.closed)
        assertEquals(3, parent.childCount)
        assertEquals(7 + 54 + 12, scroll.paddingBottom)
        old.close()
        assertEquals(7 + 54 + 12, scroll.paddingBottom)
        replacement.close()
        assertEquals(7, scroll.paddingBottom)
        assertEquals(0, (bar.layoutParams as RelativeLayout.LayoutParams).bottomMargin)
    }

    @Test
    fun lazyMainPageReplacementRestoresTheOldListAndInsetsTheNewOne() {
        create()
        source.removeView(scroll)
        val next = ScrollView(activity).apply {
            setPadding(0, 0, 0, 3)
            addView(View(activity), ViewGroup.LayoutParams(400, 1800))
        }
        source.addView(next, FrameLayout.LayoutParams(-1, -1))
        settle()
        assertEquals(7, scroll.paddingBottom)
        assertEquals(3 + 54 + 12, next.paddingBottom)
        session?.close()
        assertEquals(3, next.paddingBottom)
        assertTrue(next.clipToPadding)
    }

    @Test
    fun richFindPageDoesNotRevertTheBarWhenDiscoveryCrossesTheOldTreeLimit() {
        create()
        source.removeView(scroll)
        // Cached Find headers include hundreds of hidden/layout-only nodes before the list.
        repeat(600) { source.addView(FrameLayout(activity), FrameLayout.LayoutParams(1, 1)) }
        source.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        settle()
        shadowOf(Looper.getMainLooper()).idle()
        settle()
        assertFalse(requireNotNull(session).closed)
        assertEquals(7 + 54 + 12, scroll.paddingBottom)
        assertEquals(12, (bar.layoutParams as RelativeLayout.LayoutParams).bottomMargin)
        session?.close()
        assertEquals(7, scroll.paddingBottom)
    }

    @Test
    fun anAllocatedReadingButtonWidthDoesNotConsumeTheFloatingSideMargins() {
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(TextView(activity).apply { text = "Shelf" }, LinearLayout.LayoutParams(90, 40))
        row.addView(TextView(activity).apply { text = "Audio" }, LinearLayout.LayoutParams(62, 40))
        val wrapper = LinearLayout(activity)
        val read = TextView(activity).apply { text = "Read" }
        wrapper.addView(read, LinearLayout.LayoutParams(248, 40))
        row.addView(wrapper, LinearLayout.LayoutParams(0, 64, 1f))
        assertEquals(0, GlassGeometry.sideMargin(400, minimumControlWidth(row), 16))
        assertEquals(16, GlassGeometry.sideMargin(400, minimumControlWidth(row, read), 16))
        assertEquals(248, read.layoutParams.width) // Measuring a minimum never mutates the control.
    }

    @Test
    fun toolbarOffsetCannotPushTheDirectoryBarOrLastItemPastTheWindowBottom() {
        create(GlassBarKind.DIRECTORY, 64)
        parentTop = 80
        settle()
        assertEquals(12 + 80, (bar.layoutParams as RelativeLayout.LayoutParams).bottomMargin)
        assertEquals(800 - 12, parentTop + bar.bottom)
        assertEquals(7 + 64 + 12 + 80, scroll.paddingBottom)
        parentTop = 0
        settle()
        assertEquals(12, (bar.layoutParams as RelativeLayout.LayoutParams).bottomMargin)
        assertEquals(7 + 64 + 12, scroll.paddingBottom)
    }

    @Test fun mainDimensionsResizeAndRestoreTheNativeBar() = dimensionsResizeAndRestore(GlassBarKind.MAIN)
    @Test fun detailDimensionsResizeAndRestoreTheNativeBar() = dimensionsResizeAndRestore(GlassBarKind.DETAIL)
    @Test fun directoryDimensionsResizeAndRestoreTheNativeBar() = dimensionsResizeAndRestore(GlassBarKind.DIRECTORY)

    private fun dimensionsResizeAndRestore(kind: GlassBarKind) {
        create(kind, 64)
        var clicks = 0
        button.setOnClickListener { clicks++ }
        session?.update(effects, GlassBarDimensions(96, 60, 24))
        settle(12)
        assertEquals(96, bar.height)
        assertEquals(240, bar.width)
        assertEquals(24, (bar.layoutParams as RelativeLayout.LayoutParams).bottomMargin)
        assertEquals(7 + 96 + 24, scroll.paddingBottom)
        assertEquals(40, button.layoutParams.height)
        button.performClick()
        assertEquals(1, clicks)

        session?.update(effects, GlassBarDimensions(64, 100, 0))
        settle(12)
        assertEquals(64, bar.height)
        assertEquals(400, bar.width)
        assertEquals(0, (bar.layoutParams as RelativeLayout.LayoutParams).bottomMargin)
        assertEquals(7 + 64, scroll.paddingBottom)
        session?.close()
        settle()
        assertEquals(64, bar.height)
        assertEquals(7, scroll.paddingBottom)
        assertEquals(40, button.layoutParams.height)
        assertSame(actions, button.parent)
    }

    @Test
    fun directoryHeightChangesKeepDiscountsAndDynamicExportControls() {
        create(GlassBarKind.DIRECTORY, 64)
        val discount = View(activity)
        bar.addView(discount, 0, LinearLayout.LayoutParams(-1, 32))
        val export = Button(activity).apply { text = "Export" }
        actions.addView(export, 0, LinearLayout.LayoutParams(92, 40))
        session?.update(effects, GlassBarDimensions(80, 75, 20))
        settle(12)
        assertEquals(112, bar.height)
        assertEquals(80, actions.height)
        assertEquals(7 + 112 + 20, scroll.paddingBottom)
        assertSame(actions, export.parent)
        discount.visibility = View.GONE
        settle()
        assertEquals(80, bar.height)
        assertEquals(7 + 80 + 20, scroll.paddingBottom)
        session?.close()
        settle()
        assertEquals(64, actions.height)
        assertSame(actions, export.parent)
    }
}
