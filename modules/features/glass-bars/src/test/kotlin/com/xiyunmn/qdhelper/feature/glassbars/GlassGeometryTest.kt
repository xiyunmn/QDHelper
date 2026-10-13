package com.xiyunmn.qdhelper.feature.glassbars

import org.junit.Assert.assertEquals
import org.junit.Test

class GlassGeometryTest {
    @Test
    fun widthChoicesRespectContentAndBottomGapsAccountForNavigationMode() {
        assertEquals(80, GlassGeometry.widthSideMargin(400, 180, 60, 200))
        assertEquals(0, GlassGeometry.widthSideMargin(400, 180, 100, 200))
        assertEquals(100, GlassGeometry.widthSideMargin(400, 180, 0, 200))
        assertEquals(40, GlassGeometry.widthSideMargin(400, 320, 60, 200))
        assertEquals(0, GlassGeometry.widthSideMargin(200, 320, 60, 200))
        assertEquals(8, GlassGeometry.bottomGapDp(-1, 24))
        assertEquals(28, GlassGeometry.bottomGapDp(-1, 0))
        assertEquals(0, GlassGeometry.bottomGapDp(0, 24))
        assertEquals(36, GlassGeometry.bottomGapDp(36, 0))
    }

    @Test
    fun imeResizedDecorStillCountsSpaceBelowTheWindowViewportAsConsumed() {
        val consumed = GlassGeometry.consumedBottom(windowBottom = 1400, rootBottom = 800, viewportBottom = 800)
        assertEquals(600, consumed)
        assertEquals(36, GlassGeometry.bottomMargin(0, 80, consumed, 36))
        assertEquals(80, GlassGeometry.consumedBottom(1400, 1400, 1320))
        assertEquals(0, GlassGeometry.consumedBottom(1400, 1400, 1400))
    }

    @Test
    fun navigationInsetsAreConsumedExactlyOnceIncludingResizeForIme() {
        assertEquals(116, GlassGeometry.bottomMargin(0, 80, 0, 36))
        assertEquals(116, GlassGeometry.bottomMargin(80, 80, 0, 36))
        assertEquals(36, GlassGeometry.bottomMargin(0, 80, 80, 36))
        assertEquals(76, GlassGeometry.bottomMargin(0, 80, 40, 36))
        assertEquals(36, GlassGeometry.bottomMargin(0, 80, 700, 36))
        assertEquals(56, GlassGeometry.bottomMargin(20, 0, 0, 36))
    }

    @Test
    fun narrowScreensGiveSpaceBackToControls() {
        assertEquals(16, GlassGeometry.sideMargin(360, 308, 16))
        assertEquals(6, GlassGeometry.sideMargin(320, 308, 16))
        assertEquals(0, GlassGeometry.sideMargin(280, 308, 16))
    }

    @Test
    fun onlyTheVisibleOverlapBecomesTrailingSpace() {
        assertEquals(300, GlassGeometry.occlusion(1200, 1000, 900, true))
        assertEquals(0, GlassGeometry.occlusion(800, 1000, 900, true))
        assertEquals(0, GlassGeometry.occlusion(1000, 1000, 1100, true))
        assertEquals(0, GlassGeometry.occlusion(1000, 1000, 900, false))
        assertEquals(100, GlassGeometry.flutterPadding(24, 100))
        assertEquals(120, GlassGeometry.flutterPadding(120, 100))
    }

    @Test
    fun aDirectoryContainerExtendingBelowTheWindowIsLiftedBackIntoTheViewport() {
        val viewport = GlassGeometry.viewportBottom(parentBottom = 3328, rootBottom = 3168)
        assertEquals(3168, viewport)
        assertEquals(208, GlassGeometry.bottomMargin(0, 0, 0, 48, clippedBottom = 3328 - viewport))
        assertEquals(328, GlassGeometry.bottomMargin(0, 120, 0, 48, clippedBottom = 160))
        assertEquals(464, GlassGeometry.occlusion(3328, viewport, 2864, true))
    }
}
