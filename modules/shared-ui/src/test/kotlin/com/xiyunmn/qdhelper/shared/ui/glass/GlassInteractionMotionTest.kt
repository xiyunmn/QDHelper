package com.xiyunmn.qdhelper.shared.ui.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class GlassInteractionMotionTest {
    @Test
    fun releaseHoldsTheRaisedDropletUntilArrivalThenStopsEverySpring() {
        val motion = GlassInteractionMotion()
        motion.press()
        repeat(90) { motion.advance(1f / 60f, 0f, false) }
        assertEquals(1f, motion.progress, 0.001f)
        assertEquals(78f / 56f, motion.scaleY, 0.001f)
        motion.release()
        repeat(30) { motion.advance(1f / 60f, 0f, false) }
        assertEquals(1f, motion.progress, 0.001f)
        assertTrue(motion.highlightProgress < 0.02f)
        repeat(240) { motion.advance(1f / 60f, 0f, true) }
        assertEquals(0f, motion.progress, 0f)
        assertEquals(1f, motion.scaleX, 0f)
        assertEquals(1f, motion.scaleY, 0f)
        assertFalse(motion.running)
    }

    @Test
    fun reversingDragChangesTheDeformationAndDisablingPressResetsEveryEffect() {
        val left = GlassInteractionMotion().apply { press(); drag(-300f) }
        val right = GlassInteractionMotion().apply { press(); drag(300f) }
        repeat(30) {
            left.advance(1f / 60f, -4f, false)
            right.advance(1f / 60f, 4f, false)
        }
        assertTrue(left.scaleX < left.scaleY)
        assertTrue(right.scaleX > right.scaleY)
        repeat(60) { right.advance(1f / 120f, -4f, false) }
        assertTrue(right.scaleX < right.scaleY)
        assertTrue(right.scaleX in 0.5f..2f && right.scaleY in 0.5f..2f)
        left.configure(false)
        left.press()
        left.release()
        assertEquals(0f, left.progress, 0f)
        assertEquals(0f, left.highlightProgress, 0f)
        assertEquals(0f, left.dragDistance, 0f)
        assertFalse(left.running)
    }

    @Test
    fun deformationUsesVisibleTabTravelAndIsIndependentOfDensityAndTabCount() {
        // A complete first-to-last swipe per second has the same speed for two or five tabs.
        for (travel in listOf(90f, 270f, 360f, 1080f)) {
            assertEquals(1f, GlassInteractionMotion.normalizedVelocity(travel, travel), 0f)
            assertEquals(-1f, GlassInteractionMotion.normalizedVelocity(-travel, travel), 0f)
        }
        assertEquals(0f, GlassInteractionMotion.normalizedVelocity(300f, 0f), 0f)
        assertEquals(0f, GlassInteractionMotion.normalizedVelocity(Float.NaN, 300f), 0f)
        assertEquals(0f, GlassInteractionMotion.normalizedVelocity(300f, Float.POSITIVE_INFINITY), 0f)
    }

    @Test
    fun programmaticSelectionRaisesTheLensWithoutInventingAFingerLight() {
        val motion = GlassInteractionMotion()
        motion.press(interactive = false)
        repeat(90) { motion.advance(1f / 60f, 0f, false) }
        assertEquals(1f, motion.progress, 0.001f)
        assertEquals(0f, motion.highlightProgress, 0f)
        motion.release()
        repeat(240) { motion.advance(1f / 60f, 0f, true) }
        assertFalse(motion.running)
    }

    @Test
    fun rubberBandIsBoundedAndGravityNoiseDoesNotScheduleConstantRedraws() {
        for (distance in listOf(0f, 1f, 200f, 900f)) {
            val offset = GlassInteractionMotion.rubberBand(distance, 300f, 2f)
            assertTrue(offset in 0f..8f)
            assertEquals(-offset, GlassInteractionMotion.rubberBand(-distance, 300f, 2f), 0.0001f)
        }
        assertEquals(0f, GlassInteractionMotion.rubberBand(0f, 300f, 2f), 0f)
        assertEquals(GlassTilt.REST_ANGLE, GlassTilt.quantizedAngle(Float.NaN, 1f), 0f)
        val upright = GlassTilt.quantizedAngle(0f, -1f)
        for (jitter in listOf(-0.01f, 0f, 0.01f)) assertEquals(upright, GlassTilt.quantizedAngle(jitter, -1f), 0f)
        assertEquals((PI / 2).toFloat(), GlassTilt.quantizedAngle(0f, 1f), 0.001f)
    }
}
