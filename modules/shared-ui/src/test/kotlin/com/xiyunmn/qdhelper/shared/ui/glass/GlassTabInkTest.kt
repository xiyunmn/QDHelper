package com.xiyunmn.qdhelper.shared.ui.glass

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** Rasterize the actual GPU program: alpha-only tinting loses opaque white icon details. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlassTabInkTest {
    private val brand = 0xFFE5352F.toInt()
    private val neutral = 0xFF202124.toInt()

    private fun render(colors: IntArray, target: Int, selected: Boolean, night: Boolean = false): IntArray {
        val source = Bitmap.createBitmap(colors, colors.size, 1, Bitmap.Config.ARGB_8888)
        val output = Bitmap.createBitmap(colors.size, 1, Bitmap.Config.ARGB_8888)
        try {
            val shader = RuntimeShader(GLASS_TAB_INK_AGSL).apply {
                setInputShader("content", BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
                setColorUniform("inkColor", target)
                setColorUniform("brandColor", brand)
                setFloatUniform("night", if (night) 1f else 0f)
                setFloatUniform("origin", 0f, 0f)
                setFloatUniform("selectedBounds", 0f, 0f, if (selected) colors.size.toFloat() else 0f, 1f)
            }
            val canvas = Canvas(output)
            ReflectionHelpers.callInstanceMethod<Unit>(canvas, "setHwFeaturesInSwModeEnabled",
                ClassParameter.from(Boolean::class.javaPrimitiveType, true))
            canvas.drawRect(0f, 0f, colors.size.toFloat(), 1f, Paint().apply { this.shader = shader })
            return IntArray(colors.size) { output.getPixel(it, 0) }
        } finally { source.recycle(); output.recycle() }
    }

    @Test fun selectedDiamondAndScrollToTopKeepTheirOpaqueWhiteMarksInBothRows() {
        // Both host assets use opaque white strokes on red, not transparent cutouts.
        val pixels = intArrayOf(brand, Color.WHITE, brand, Color.TRANSPARENT)
        for (target in listOf(brand, neutral)) {
            val result = render(pixels, target, selected = true)
            assertEquals(target, result[0])
            assertEquals("White arrow/diamond stroke must not be filled", Color.WHITE, result[1])
            assertEquals(target, result[2])
            assertEquals(Color.TRANSPARENT, result[3])
        }
    }

    @Test fun inactiveOutlineRecolorsWithoutChangingCoverageOrOtherArtworkColors() {
        val result = render(intArrayOf(Color.BLACK, 0x80000000.toInt(), Color.TRANSPARENT, Color.BLUE, Color.GREEN), brand, false)
        assertEquals(brand, result[0])
        assertEquals(128, Color.alpha(result[1]))
        assertEquals(0, Color.alpha(result[2]))
        assertEquals(Color.BLUE, result[3])
        assertEquals(Color.GREEN, result[4])
    }

    @Test fun nightSelectedIconRetainsContrastAndInactiveWhiteInkCanHighlight() {
        val result = render(intArrayOf(brand, Color.WHITE, Color.TRANSPARENT), Color.WHITE, true, night = true)
        assertEquals(Color.WHITE, result[0])
        assertTrue("Internal mark must contrast with neutral white ink", Color.red(result[1]) < 40)
        assertEquals(0, Color.alpha(result[2]))
        val selected = render(intArrayOf(brand, Color.WHITE), brand, true, night = true)
        assertEquals(brand, selected[0])
        assertEquals(Color.WHITE, selected[1])
        val normal = render(intArrayOf(Color.WHITE, Color.BLACK), brand, false, night = true)
        assertEquals(brand, normal[0])
        assertEquals(Color.BLACK, normal[1])
    }
}
