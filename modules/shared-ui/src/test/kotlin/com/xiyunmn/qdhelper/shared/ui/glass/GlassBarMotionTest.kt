package com.xiyunmn.qdhelper.shared.ui.glass

import android.view.MotionEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class GlassBarMotionTest {
    @Test
    fun missingOrHiddenSelectedTabInvalidatesOnlyOnce() {
        val motion = motion()
        var redraws = 0
        motion.onChanged = { redraws++ }
        motion.clearSelection()
        val cleared = redraws
        repeat(120) { motion.clearSelection() }
        assertEquals(1, cleared)
        assertEquals(cleared, redraws)
        assertFalse(motion.running)
        motion.setSelection(104f, 4f, 196f, 56f)
        assertEquals(150f, motion.selectionCenterX!!, .001f)
    }

    private fun motion(press: Boolean = true, animate: Boolean = true) = GlassBarMotion(1f).apply {
        configure(300, 60, press, animate)
        setSelectionTravelRange(50f, 250f)
        setSelection(4f, 4f, 96f, 56f)
    }

    @Test
    fun dragReversesImmediatelyAndKeepsPickupOffset() {
        val motion = motion()
        motion.beginSelectionDrag(70f, 100)
        motion.observeTouch(MotionEvent.ACTION_DOWN, 70f, 30f)
        motion.moveSelectionDrag(270f, 110)
        assertEquals(250f, motion.selectionCenterX!!, 0.001f)
        motion.moveSelectionDrag(120f, 112)
        assertEquals(100f, motion.selectionCenterX!!, 0.001f)
        motion.moveSelectionDrag(-100f, 114)
        assertEquals(50f, motion.selectionCenterX!!, 0.001f)
    }

    @Test
    fun actualSampleTimingChangesDeformationButNeverCenter() {
        fun drag(delta: Long) = motion().apply {
            beginSelectionDrag(50f, 100)
            observeTouch(MotionEvent.ACTION_DOWN, 50f, 30f)
            moveSelectionDrag(200f, 100 + delta)
            advance(1f / 120f)
        }
        val fast = drag(8)
        val slow = drag(80)
        assertEquals(fast.selectionCenterX, slow.selectionCenterX)
        assertTrue(fast.scaleX > slow.scaleX)
    }

    @Test
    fun releaseSettlesAtBothRefreshRatesAndStopsScheduling() {
        for (hz in listOf(60, 120)) {
            val motion = motion()
            motion.beginSelectionDrag(50f, 100)
            motion.observeTouch(MotionEvent.ACTION_DOWN, 50f, 30f)
            motion.moveSelectionDrag(215f, 120)
            repeat(hz / 5) { motion.advance(1f / hz) }
            motion.setSelection(204f, 4f, 296f, 56f)
            motion.observeTouch(MotionEvent.ACTION_UP, 215f, 30f)
            repeat(hz * 3) { motion.advance(1f / hz) }
            assertEquals(250f, motion.selectionCenterX!!, 0.001f)
            assertEquals(0f, motion.progress, 0f)
            assertFalse(motion.running)
        }
    }

    @Test
    fun focusLossRestoresActualSelectionAndRemovesPress() {
        val motion = motion()
        motion.beginSelectionDrag(50f, 100)
        motion.observeTouch(MotionEvent.ACTION_DOWN, 50f, 30f)
        motion.moveSelectionDrag(250f, 120)
        motion.advance(0.016f)
        motion.setActive(false)
        assertEquals(50f, motion.selectionCenterX!!, 0.001f)
        assertEquals(0f, motion.progress, 0f)
        assertFalse(motion.running)
        motion.setActive(true)
        assertFalse(motion.running)
    }

    @Test
    fun noAnimationStillSupportsImmediateDragAndSelection() {
        val motion = motion(animate = false)
        motion.beginSelectionDrag(50f, 100)
        motion.observeTouch(MotionEvent.ACTION_DOWN, 50f, 30f)
        motion.moveSelectionDrag(250f, 110)
        assertEquals(250f, motion.selectionCenterX!!, 0.001f)
        motion.setSelection(204f, 4f, 296f, 56f)
        motion.observeTouch(MotionEvent.ACTION_UP, 250f, 30f)
        assertEquals(250f, motion.selectionCenterX!!, 0.001f)
        assertFalse(motion.running)
    }
}
