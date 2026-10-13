package com.xiyunmn.qdhelper.shared.ui.glass

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
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
import kotlin.math.abs

/** Rasterizes the production lens/transform, including glyph-like foreground content and its rim. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlassLensTransformTest {
    private val bounds = RectF(110f, 60f, 190f, 116f)

    @Test
    fun dragPreservesNativeIconLabelAndBadgeProportionsAtEveryTabPosition() {
        // The host capture already includes the tab's uniform press zoom. Lens stretching
        // must not magnify it again, or stretch glyphs vertically when drag direction changes.
        for (left in listOf(14f, 110f, 206f)) {
            val selection = RectF(left, 60f, left + 80f, 116f)
            val content = nativeTab(selection)
            try {
                for ((scaleX, scaleY) in listOf(1.6f to 1.1f, 1.16f to 1.5f, 78f / 56f to 78f / 56f)) {
                    val output = lens(content, scaleX, scaleY, selection)
                    try { assertContentAligned(content, output) } finally { output.recycle() }
                }
            } finally { content.recycle() }
        }
    }

    @Test
    fun disablingRefractionKeepsContentAlignedDuringScaleRebound() {
        val content = nativeTab(bounds)
        val output = lens(content, 0.94f, 1.1f, amount = 0f)
        try { assertContentAligned(content, output) } finally {
            content.recycle()
            output.recycle()
        }
    }

    @Test
    fun finishedLensKeepsItsAffineContourInsteadOfResizingItsCornerRadius() {
        val content = nativeTab(bounds)
        val forward = lens(content, 1.6f, 1.1f)
        val reverse = lens(content, 1.16f, 1.5f)
        try {
            // This point would be inside a newly rounded, wider pill but is outside the
            // affine ellipse. Refraction and highlights must share the same contour.
            assertEquals(0, Color.alpha(forward.getPixel(105, 60)))
            assertTrue(Color.alpha(forward.getPixel(110, 65)) >= 250)
            assertEquals(0, Color.alpha(reverse.getPixel(150, 40)))
            assertTrue(Color.alpha(reverse.getPixel(150, 49)) >= 250)
        } finally {
            listOf(content, forward, reverse).forEach(Bitmap::recycle)
        }
    }

    @Test
    fun specularPeaksFollowTheStretchedContourAndKeepTheLensInteriorClear() {
        val rest = highlight(1f, 1f)
        val forward = highlight(1.6f, 1.1f)
        try {
            assertTrue((87..92).all { x -> Color.alpha(rest.getPixel(x, 88)) == 0 })
            assertTrue((87..92).any { x -> Color.red(forward.getPixel(x, 88)) > 0 })
            assertEquals(0, Color.alpha(forward.getPixel(150, 88)))
            assertEquals(0, Color.alpha(forward.getPixel(105, 60)))
        } finally {
            rest.recycle()
            forward.recycle()
        }
    }

    private fun nativeTab(selection: RectF): Bitmap = Bitmap.createBitmap(300, 176, Bitmap.Config.ARGB_8888).also {
        Canvas(it).apply {
            drawColor(Color.LTGRAY)
            translate(selection.centerX(), selection.centerY())
            drawRect(-6f, -10f, 6f, 2f, Paint().apply { color = Color.RED })
            drawRect(-10f, 8f, 10f, 12f, Paint().apply { color = Color.GREEN })
            drawRect(6f, -11f, 10f, -7f, Paint().apply { color = Color.BLUE })
        }
    }

    private fun assertContentAligned(source: Bitmap, output: Bitmap) {
        for (color in listOf(Color.RED, Color.GREEN, Color.BLUE)) {
            val expected = colorBounds(source, color)
            val actual = colorBounds(output, color)
            assertTrue(
                "Native content stretched or moved: $expected -> $actual",
                abs(expected.left - actual.left) <= 1 && abs(expected.top - actual.top) <= 1 &&
                    abs(expected.right - actual.right) <= 1 && abs(expected.bottom - actual.bottom) <= 1,
            )
        }
    }

    private fun colorBounds(bitmap: Bitmap, color: Int): Rect {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val rect = Rect(bitmap.width, bitmap.height, 0, 0)
        pixels.forEachIndexed { index, pixel ->
            // Count antialiased coverage, not only the fully saturated interior of a glyph.
            if (abs(Color.red(pixel) - Color.red(color)) < 128 &&
                abs(Color.green(pixel) - Color.green(color)) < 128 &&
                abs(Color.blue(pixel) - Color.blue(color)) < 128
            ) {
                val x = index % bitmap.width
                val y = index / bitmap.width
                rect.left = minOf(rect.left, x)
                rect.top = minOf(rect.top, y)
                rect.right = maxOf(rect.right, x + 1)
                rect.bottom = maxOf(rect.bottom, y + 1)
            }
        }
        assertTrue("Missing native content: $color", !rect.isEmpty)
        return rect
    }

    private fun lens(
        content: Bitmap,
        scaleX: Float,
        scaleY: Float,
        bounds: RectF = this.bounds,
        amount: Float = -14f,
    ): Bitmap {
        val backdrop = Bitmap.createBitmap(content.width, content.height, Bitmap.Config.ARGB_8888)
        Canvas(backdrop).apply {
            withGlassLensTransform(bounds, scaleX, scaleY, inverse = true) {
                drawBitmap(content, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
            }
        }
        val shader = RuntimeShader(GLASS_LENS_AGSL).apply {
            setInputShader("content", BitmapShader(backdrop, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
            setFloatUniform("size", bounds.width(), bounds.height())
            setFloatUniform("offset", -bounds.left, -bounds.top)
            setFloatUniform("radius", bounds.height() / 2f)
            setFloatUniform("bevel", 10f)
            setFloatUniform("refraction", amount)
            setFloatUniform("depth", 1f)
            setFloatUniform("dispersion", 0.5f)
            setFloatUniform("sampleLo", 0.5f, 0.5f)
            setFloatUniform("sampleHi", content.width - 0.5f, content.height - 0.5f)
        }
        val outline = Path().apply { addRoundRect(bounds, 28f, 28f, Path.Direction.CW) }
        return Bitmap.createBitmap(300, 176, Bitmap.Config.ARGB_8888).also { output ->
            val canvas = shaderCanvas(output)
            canvas.withGlassLensTransform(bounds, scaleX, scaleY) {
                canvas.clipPath(outline)
                canvas.drawRect(0f, 0f, 300f, 176f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader })
            }
            backdrop.recycle()
        }
    }

    private fun highlight(scaleX: Float, scaleY: Float): Bitmap {
        val shader = RuntimeShader(GlassLighting.SHADER).apply {
            setFloatUniform("halfView", 40f, 28f)
            setFloatUniform("halfViewFloor", 40f, 28f)
            setFloatUniform("radius", 28f)
            setFloatUniform("strokeWidth", 1f)
            setFloatUniform("innerBlurRadius", 2f)
            setFloatUniform("highlightAlpha", 1f)
            setFloatUniform("lightDir1", 0.7f, -0.7f, -0.05f)
            setFloatUniform("lightDir2", 0f, 0.2f, -0.98f)
        }
        return Bitmap.createBitmap(300, 176, Bitmap.Config.ARGB_8888).also { output ->
            val canvas = shaderCanvas(output)
            canvas.withGlassLensTransform(bounds, scaleX, scaleY) {
                canvas.translate(bounds.left, bounds.top)
                canvas.drawRect(0f, 0f, bounds.width(), bounds.height(), Paint().apply { this.shader = shader })
            }
        }
    }

    private fun shaderCanvas(bitmap: Bitmap): Canvas = Canvas(bitmap).also {
        ReflectionHelpers.callInstanceMethod<Unit>(
            it, "setHwFeaturesInSwModeEnabled", ClassParameter.from(Boolean::class.javaPrimitiveType, true),
        )
    }
}
