package com.xiyunmn.qdhelper.shared.ui.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassSelectionMotionTest {
    @Test
    fun rapidTabChangesRetargetTheSameDropletAndStopRequestingFramesWhenSettled() {
        val motion = GlassSelectionMotion()
        motion.target(0.01f, 0.08f, 0.24f, 0.92f, true)
        assertFalse(motion.running)
        motion.target(0.76f, 0.08f, 0.99f, 0.92f, true)
        repeat(4) { motion.advance(1f / 120f) }
        assertTrue(motion.left in 0.01f..0.76f)
        assertTrue(motion.velocity > 0f)
        motion.target(0.26f, 0.08f, 0.49f, 0.92f, true)
        repeat(240) { motion.advance(1f / 60f) }
        assertEquals(0.26f, motion.left, 0f)
        assertEquals(0.49f, motion.right, 0f)
        assertEquals(0f, motion.velocity, 0f)
        assertFalse(motion.running)
    }

    @Test
    fun disabledAnimationsAndLifecycleSuspensionLeaveAnAccurateStaticSelection() {
        val motion = GlassSelectionMotion()
        motion.target(0f, 0.1f, 0.2f, 0.9f, true)
        motion.target(0.4f, 0.1f, 0.6f, 0.9f, true)
        motion.advance(0.2f) // A stalled frame is subdivided and clamped.
        assertTrue(motion.left.isFinite())
        motion.settle()
        assertEquals(0.4f, motion.left, 0f)
        assertFalse(motion.running)
        motion.target(0.8f, 0.1f, 1f, 0.9f, false)
        assertEquals(0.8f, motion.left, 0f)
        assertFalse(motion.running)
        motion.clear()
        assertFalse(motion.visible)
        assertFalse(motion.running)
    }
}
