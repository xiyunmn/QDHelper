package com.xiyunmn.qdhelper.shared.ui.glass

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RuntimeShader
import android.graphics.Shader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** Native Skia compiles/rasterizes the real programs; device rendering has a separate acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlassLensShaderTest {
    private fun canvas(bitmap: Bitmap): Canvas = Canvas(bitmap).also {
        ReflectionHelpers.callInstanceMethod<Unit>(
            it, "setHwFeaturesInSwModeEnabled", ClassParameter.from(Boolean::class.javaPrimitiveType, true),
        )
    }

    @Test
    fun lensPreservesItsCenterRefractsTheRimAndClipsAtTheFinalOutline() {
        val width = 224
        val height = 124
        val background = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) for (x in 0 until width) {
            background.setPixel(x, y, if (x / 3 % 2 == 0) Color.RED else Color.BLUE)
        }
        val shader = RuntimeShader(GLASS_LENS_AGSL)
        shader.setInputShader("content", BitmapShader(background, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        shader.setFloatUniform("size", 160f, 60f)
        shader.setFloatUniform("offset", -32f, -32f)
        shader.setFloatUniform("radius", 30f)
        shader.setFloatUniform("bevel", 24f)
        shader.setFloatUniform("depth", 0f)
        shader.setFloatUniform("dispersion", 0f)
        shader.setFloatUniform("sampleLo", 0.5f, 0.5f)
        shader.setFloatUniform("sampleHi", width - 0.5f, height - 0.5f)
        val outline = Path().apply { addRoundRect(32f, 32f, 192f, 92f, 30f, 30f, Path.Direction.CW) }
        fun render(amount: Float): Bitmap {
            shader.setFloatUniform("refraction", amount)
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                canvas(bitmap).apply {
                    clipPath(outline)
                    drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply { this.shader = shader })
                }
            }
        }
        val flat = render(0f)
        val lens = render(-24f)
        assertEquals(0, Color.alpha(lens.getPixel(0, 0)))
        assertEquals(background.getPixel(112, 62), lens.getPixel(112, 62))
        assertTrue((34..42).any { x -> flat.getPixel(x, 62) != lens.getPixel(x, 62) })
        shader.setFloatUniform("depth", 1f)
        shader.setFloatUniform("dispersion", 0.5f)
        shader.setFloatUniform("bevel", 10f)
        val pressed = render(-14f)
        assertEquals(background.getPixel(112, 62), pressed.getPixel(112, 62))
        assertTrue((34..44).all { x -> Color.alpha(pressed.getPixel(x, 62)) >= 250 })
        assertTrue((40..55).any { x -> lens.getPixel(x, 47) != pressed.getPixel(x, 47) })
        listOf(background, flat, lens, pressed).forEach(Bitmap::recycle)
    }

    @Test
    fun interactiveBloomIsPremultipliedAndNeverTurnsItsTwelvePercentPeakOpaque() {
        val shader = RuntimeShader(GLASS_BLOOM_AGSL)
        shader.setFloatUniform("alpha", 0.12f)
        shader.setFloatUniform("radius", 30f)
        shader.setFloatUniform("position", 50f, 50f)
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        canvas(bitmap).drawRect(0f, 0f, 100f, 100f, Paint().apply { this.shader = shader })
        assertTrue(Color.alpha(bitmap.getPixel(50, 50)) in 29..32)
        assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
        bitmap.recycle()
    }

    @Test
    fun specularShaderHasFiniteOutputAtStraightEdgesAndCapsuleCenters() {
        val shader = RuntimeShader(GlassLighting.SHADER)
        shader.setFloatUniform("halfView", 80f, 30f)
        shader.setFloatUniform("halfViewFloor", 80f, 30f)
        shader.setFloatUniform("radius", 30f)
        shader.setFloatUniform("strokeWidth", 1f)
        shader.setFloatUniform("innerBlurRadius", 2f)
        shader.setFloatUniform("highlightAlpha", 0.75f)
        shader.setFloatUniform("lightDir1", 0.7f, -0.7f, -0.05f)
        shader.setFloatUniform("lightDir2", 0f, 0.2f, -0.98f)
        val bitmap = Bitmap.createBitmap(160, 60, Bitmap.Config.ARGB_8888)
        canvas(bitmap).drawRect(0f, 0f, 160f, 60f, Paint().apply { this.shader = shader })
        assertEquals(0, Color.alpha(bitmap.getPixel(80, 30)))
        assertTrue((1..3).any { y -> Color.alpha(bitmap.getPixel(80, y)) > 0 })
        bitmap.recycle()
    }
}
