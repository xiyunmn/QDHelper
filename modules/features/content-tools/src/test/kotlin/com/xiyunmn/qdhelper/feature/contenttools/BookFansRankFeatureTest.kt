package com.xiyunmn.qdhelper.feature.contenttools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookFansRankFeatureTest {
    @Test
    fun `reveals exact rank only where host hides ranks above ninety nine`() {
        assertFalse(shouldRevealExactFanRank(0))
        assertFalse(shouldRevealExactFanRank(99))
        assertTrue(shouldRevealExactFanRank(100))
        assertTrue(shouldRevealExactFanRank(500))
    }

    @Test
    fun `uses last native-visible rank while host renders exact hidden ranks`() {
        assertEquals(99, nativeRenderableFanRank(99))
        assertEquals(99, nativeRenderableFanRank(100))
        assertEquals(99, nativeRenderableFanRank(500))
    }
}
