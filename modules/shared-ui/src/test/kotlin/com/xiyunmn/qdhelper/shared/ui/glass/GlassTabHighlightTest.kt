package com.xiyunmn.qdhelper.shared.ui.glass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlassTabHighlightTest {
    private val neutral = Color.rgb(32, 32, 32)
    private val accent = Color.rgb(229, 53, 47)
    private fun content() = GlassTabContent(
        listOf(RectF(0f, 0f, 100f, 64f), RectF(100f, 0f, 200f, 64f)),
        listOf(RectF(28f, 16f, 76f, 52f), RectF(128f, 16f, 176f, 52f)),
        listOf(RectF(66f, 18f, 75f, 27f)), neutral, accent)

    private fun nativeInk(): Bitmap = Bitmap.createBitmap(200, 64, Bitmap.Config.ARGB_8888).also { bitmap ->
        Canvas(bitmap).apply {
            val paint = Paint().apply { color = Color.MAGENTA }
            drawRect(30f, 20f, 70f, 44f, paint)
            drawRect(130f, 20f, 170f, 44f, paint)
            drawRect(30f, 48f, 70f, 51f, paint)
            drawRect(130f, 48f, 170f, 51f, paint)
            paint.color = Color.BLUE
            drawRect(66f, 18f, 75f, 27f, paint)
        }
    }

    private fun render(source: Bitmap, capsule: RectF, progress: Float = 0f): Bitmap {
        val highlight = GlassTabHighlight().apply { update(content()) }
        return Bitmap.createBitmap(200, 64, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            val draw: (Canvas) -> Unit = { it.drawBitmap(source, 0f, 0f, null) }
            highlight.drawNeutral(canvas, draw)
            val save = canvas.save()
            canvas.clipPath(Path().apply { addRoundRect(capsule, 28f, 28f, Path.Direction.CW) })
            canvas.drawColor(Color.WHITE)
            highlight.drawAccent(canvas, progress, draw)
            canvas.restoreToCount(save)
        }
    }

    @Test fun restingSelectionHasAccentIconsAndLabelsWithoutPressing() {
        val source = nativeInk()
        val result = render(source, RectF(4f, 4f, 96f, 60f))
        try {
            assertEquals(accent, result.getPixel(50, 32))
            assertEquals(accent, result.getPixel(50, 49))
            assertEquals(neutral, result.getPixel(150, 32))
            assertEquals(neutral, result.getPixel(150, 49))
            assertEquals(Color.BLUE, result.getPixel(70, 22))
            assertEquals(Color.MAGENTA, source.getPixel(50, 32))
        } finally { source.recycle(); result.recycle() }
    }

    @Test fun crossingTwoTabsRevealsBothPartialIconsInsteadOfSwitchingTheWholeNearestTab() {
        val source = nativeInk()
        val result = render(source, RectF(60f, 4f, 140f, 60f))
        try {
            assertEquals(neutral, result.getPixel(35, 32))
            assertEquals(accent, result.getPixel(65, 32))
            assertEquals(accent, result.getPixel(135, 32))
            assertEquals(neutral, result.getPixel(165, 32))
        } finally { source.recycle(); result.recycle() }
    }

    @Test fun accentLayerScalesBothTabsAndPreservesBadgeColors() {
        val source = nativeInk()
        val result = render(source, RectF(0f, 0f, 200f, 64f), 1f)
        try {
            assertEquals(accent, result.getPixel(27, 32))
            assertEquals(accent, result.getPixel(127, 32))
            assertEquals(Color.BLUE, result.getPixel(73, 20))
            assertEquals(0, source.getPixel(27, 32))
        } finally { source.recycle(); result.recycle() }
    }

    @Test fun tintPreservesInkAlphaAndAcceptsThemeUpdates() {
        val bitmap = Bitmap.createBitmap(200, 64, Bitmap.Config.ARGB_8888)
        val highlight = GlassTabHighlight()
        val draw: (Canvas) -> Unit = { it.drawRect(40f, 20f, 60f, 40f, Paint().apply { color = 0x80FF0000.toInt() }) }
        try {
            for (color in listOf(neutral, Color.WHITE)) {
                bitmap.eraseColor(Color.TRANSPARENT)
                highlight.update(content().copy(neutralColor = color))
                highlight.drawNeutral(Canvas(bitmap), draw)
                assertEquals(128, Color.alpha(bitmap.getPixel(50, 32)))
                assertTrue(kotlin.math.abs(Color.red(color) - Color.red(bitmap.getPixel(50, 32))) <= 1)
            }
        } finally { bitmap.recycle() }
    }
}
