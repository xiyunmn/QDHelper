package com.xiyunmn.qdhelper.feature.glassbars

import android.app.Activity
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.xiyunmn.qdhelper.shared.ui.glass.GlassBarMotion
import com.xiyunmn.qdhelper.shared.ui.glass.GlassEffectsSpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35], qualifiers = "w400dp-h800dp-mdpi")
class GlassTabSelectionTest {
    class Strip(context: Context) : FrameLayout(context) {
        var selected = 0
        fun getCurrentItem() = selected
    }

    private val controller = Robolectric.buildActivity(Activity::class.java).setup()
    private val activity = controller.get()
    private val bar = FrameLayout(activity)
    private val strip = Strip(activity)
    private val row = LinearLayout(activity)
    private val selection = GlassTabSelection(bar, strip)
    private val origin = IntArray(2)
    private var clicks = 0

    @Before
    fun setUp() {
        strip.addView(row, FrameLayout.LayoutParams(300, 60))
        bar.addView(strip, FrameLayout.LayoutParams(300, 60))
        activity.setContentView(bar, FrameLayout.LayoutParams(300, 60).apply { leftMargin = 20; topMargin = 500 })
        repeat(3) { index ->
            row.addView(View(activity).apply {
                setOnClickListener { clicks++; strip.selected = index }
            }, LinearLayout.LayoutParams(150, 60))
        }
        bar.layout(20, 500, 320, 560)
        strip.layout(0, 0, 300, 60)
        row.layout(0, 0, 300, 60)
        row.getChildAt(0).layout(0, 0, 150, 60)
        row.getChildAt(1).visibility = View.GONE
        row.getChildAt(2).layout(150, 0, 300, 60)
        bar.getLocationOnScreen(origin)
    }

    @After
    fun tearDown() {
        selection.cancelTouch()
        controller.pause().stop().destroy()
    }

    private fun event(action: Int, x: Float, y: Float = 30f) =
        selection.observeTouch(action, origin[0] + x, origin[1] + y)

    @Test
    fun releaseClicksTheVisibleNativeTargetOnceAndCancelDoesNotNavigate() {
        event(MotionEvent.ACTION_DOWN, 75f)
        event(MotionEvent.ACTION_MOVE, 225f)
        assertEquals(0, strip.selected)
        assertEquals(1f, row.getChildAt(0).scaleX, 0f)
        assertEquals(1f, row.getChildAt(2).scaleX, 0.001f)
        event(MotionEvent.ACTION_UP, 225f)
        assertEquals(2, strip.selected)
        assertEquals(1, clicks)
        assertEquals(1f, row.getChildAt(2).scaleX, 0f)
        event(MotionEvent.ACTION_DOWN, 225f)
        event(MotionEvent.ACTION_MOVE, 75f)
        event(MotionEvent.ACTION_CANCEL, 75f)
        event(MotionEvent.ACTION_UP, 75f)
        assertEquals(2, strip.selected)
        assertEquals(1, clicks)
        event(MotionEvent.ACTION_DOWN, 225f)
        event(MotionEvent.ACTION_UP, 225f)
        assertEquals(1, clicks)
    }

    @Test
    fun draggingAboveTheBarKeepsPreviewingAndReleaseClicksTheTargetOnce() {
        event(MotionEvent.ACTION_DOWN, 75f)
        event(MotionEvent.ACTION_MOVE, 225f)
        assertEquals(1f, row.getChildAt(2).scaleX, 0.001f)

        event(MotionEvent.ACTION_MOVE, 75f, -180f)
        assertEquals(1f, row.getChildAt(0).scaleX, 0.001f)
        assertEquals(1f, row.getChildAt(2).scaleX, 0f)
        event(MotionEvent.ACTION_MOVE, 225f, -240f)
        assertEquals(1f, row.getChildAt(0).scaleX, 0f)
        assertEquals(1f, row.getChildAt(2).scaleX, 0.001f)
        assertEquals(0, strip.selected)
        assertEquals(0, clicks)

        event(MotionEvent.ACTION_UP, 225f, -240f)
        assertEquals(2, strip.selected)
        assertEquals(1, clicks)
        assertEquals(1f, row.getChildAt(2).scaleX, 0f)
        event(MotionEvent.ACTION_UP, 225f, -240f)
        assertEquals(1, clicks)
    }

    @Test
    fun releaseAboveTheBarCommitsEvenWithoutAMoveOutsideTheBar() {
        event(MotionEvent.ACTION_DOWN, 75f)
        event(MotionEvent.ACTION_MOVE, 225f)
        event(MotionEvent.ACTION_UP, 225f, -30f)
        assertEquals(2, strip.selected)
        assertEquals(1, clicks)
    }

    @Test
    fun movingUpBeforeMovingHorizontallyKeepsTheDragOffsetAndPreview() {
        event(MotionEvent.ACTION_DOWN, 120f)
        event(MotionEvent.ACTION_MOVE, 120f, -180f)
        event(MotionEvent.ACTION_MOVE, 190f, -180f)
        // The finger started 45px right of the capsule center, which is still over tab zero.
        assertEquals(1f, row.getChildAt(0).scaleX, 0.001f)
        assertEquals(1f, row.getChildAt(2).scaleX, 0f)
        event(MotionEvent.ACTION_MOVE, 225f, -180f)
        assertEquals(1f, row.getChildAt(0).scaleX, 0f)
        assertEquals(1f, row.getChildAt(2).scaleX, 0.001f)
        assertEquals(0, clicks)
        event(MotionEvent.ACTION_UP, 225f, -180f)
        assertEquals(2, strip.selected)
        assertEquals(1, clicks)
    }

    @Test
    fun cancelledOrMultitouchDragsAboveTheBarDoNotNavigate() {
        for (cancelAction in listOf(MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN)) {
            event(MotionEvent.ACTION_DOWN, 75f)
            event(MotionEvent.ACTION_MOVE, 225f, -180f)
            assertEquals(1f, row.getChildAt(2).scaleX, 0.001f)
            event(cancelAction, 225f, -180f)
            assertEquals(1f, row.getChildAt(2).scaleX, 0f)
            event(MotionEvent.ACTION_MOVE, 225f)
            event(MotionEvent.ACTION_UP, 225f)
            assertEquals(0, strip.selected)
            assertEquals(0, clicks)
        }
    }

    @Test
    fun touchesStartingAboveTheBarAndVerticalOnlyGesturesDoNotNavigate() {
        event(MotionEvent.ACTION_DOWN, 75f, -180f)
        event(MotionEvent.ACTION_MOVE, 225f)
        event(MotionEvent.ACTION_UP, 225f)
        assertEquals(0, strip.selected)
        assertEquals(0, clicks)

        event(MotionEvent.ACTION_DOWN, 75f)
        event(MotionEvent.ACTION_MOVE, 75f, -180f)
        event(MotionEvent.ACTION_UP, 75f, -180f)
        assertEquals(0, strip.selected)
        assertEquals(0, clicks)
    }

    @Test
    fun renderedCapsuleOwnsPreviewAndReleaseDuringAFastReversalAboveTheBar() = withGlass(press = true) { visual, selection ->
        fun send(action: Int, x: Float, y: Float) {
            selection.observeTouch(action, origin[0] + x, origin[1] + y)
            selection.update(visual)
            visual.observeTouch(action, x, y)
        }
        send(MotionEvent.ACTION_DOWN, 75f, 30f)
        send(MotionEvent.ACTION_MOVE, 225f, -180f)
        repeat(48) { visual.advance(1f / 120f) }
        assertEquals(1f, row.getChildAt(2).scaleX, 0.01f)
        assertEquals(0, strip.selected)
        // Reverse and lift within one frame: center/preview must already follow the finger.
        send(MotionEvent.ACTION_MOVE, 75f, -180f)
        send(MotionEvent.ACTION_UP, 75f, -180f)
        assertEquals(75f, requireNotNull(visual.selectionCenterX), 0.01f)
        assertEquals(0, strip.selected)
        assertEquals(0, clicks)
        repeat(240) { visual.advance(1f / 120f) }
        assertEquals(1f, row.getChildAt(2).scaleX, 0f)
    }

    @Test
    fun disablingPressKeepsTheSelectionVisibleAndDragNavigationWorking() = withGlass(press = false) { visual, selection ->
        fun send(action: Int, x: Float, y: Float) {
            selection.observeTouch(action, origin[0] + x, origin[1] + y)
            selection.update(visual)
            visual.observeTouch(action, x, y)
        }
        send(MotionEvent.ACTION_DOWN, 75f, 30f)
        send(MotionEvent.ACTION_MOVE, 225f, -180f)
        repeat(48) { visual.advance(1f / 120f) }
        assertEquals(225f, requireNotNull(visual.selectionCenterX), 0.5f)
        assertEquals(1f, row.getChildAt(2).scaleX, 0f)
        send(MotionEvent.ACTION_UP, 225f, -180f)
        assertEquals(2, strip.selected)
        assertEquals(1, clicks)
    }

    @Test
    fun finalUpCoordinateWinsWithoutAnotherAnimationFrame() = withGlass(press = true) { visual, native ->
        native.observeTouch(MotionEvent.ACTION_DOWN, origin[0] + 75f, origin[1] + 30f, 100)
        visual.observeTouch(MotionEvent.ACTION_DOWN, 75f, 30f)
        native.observeTouch(MotionEvent.ACTION_MOVE, origin[0] + 225f, origin[1] + 30f, 110)
        assertEquals(225f, requireNotNull(visual.selectionCenterX), 0.01f)
        native.observeTouch(MotionEvent.ACTION_UP, origin[0] + 75f, origin[1] + 30f, 112)
        native.update(visual)
        visual.observeTouch(MotionEvent.ACTION_UP, 75f, 30f)
        assertEquals(0, strip.selected)
        assertEquals(0, clicks)
    }

    @Test
    fun hostVetoRestoresRealSelectionWithoutRetry() = withGlass(press = true) { visual, native ->
        row.getChildAt(2).setOnClickListener { clicks++ }
        native.observeTouch(MotionEvent.ACTION_DOWN, origin[0] + 75f, origin[1] + 30f, 100)
        visual.observeTouch(MotionEvent.ACTION_DOWN, 75f, 30f)
        native.observeTouch(MotionEvent.ACTION_MOVE, origin[0] + 225f, origin[1] + 30f, 110)
        native.observeTouch(MotionEvent.ACTION_UP, origin[0] + 225f, origin[1] + 30f, 120)
        native.update(visual)
        visual.observeTouch(MotionEvent.ACTION_UP, 225f, 30f)
        repeat(240) { visual.advance(1f / 120f) }
        assertEquals(1, clicks)
        assertEquals(0, strip.selected)
        assertEquals(75f, requireNotNull(visual.selectionCenterX), 0.01f)
        org.junit.Assert.assertFalse(visual.running)
    }

    @Test
    fun replacingNativeRowCancelsGesture() {
        event(MotionEvent.ACTION_DOWN, 75f)
        event(MotionEvent.ACTION_MOVE, 225f)
        strip.removeAllViews()
        strip.addView(LinearLayout(activity))
        event(MotionEvent.ACTION_UP, 225f)
        assertEquals(0, clicks)
    }

    private fun withGlass(press: Boolean, test: (GlassBarMotion, GlassTabSelection) -> Unit) {
        val visual = GlassBarMotion(1f)
        visual.configure(300, 60, press, true)
        val native = GlassTabSelection(bar, strip, visual)
        native.update(visual)
        try { test(visual, native) } finally {
            native.cancelTouch()
            visual.close()
        }
    }
}
