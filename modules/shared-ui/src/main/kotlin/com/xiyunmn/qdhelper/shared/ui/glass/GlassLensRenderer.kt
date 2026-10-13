package com.xiyunmn.qdhelper.shared.ui.glass

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.view.View
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * ForbidAd4TieBa's page -> vibrancy -> blur -> lens -> surface stack, adapted for Qidian.
 * The raised droplet has separate clear/embedded materials, clipped AFTER refraction so the
 * pill's blur cannot bleed outside its outline. KernelSU's combined backdrop is built from the
 * page and a separate native-tab display list; a foreground sibling covers the un-refracted copy.
 * Both captures are disjoint from the glass and preserve the framework child-drawing path.
 */
@android.annotation.TargetApi(33)
internal class GlassLensRenderer(private val failure: (Throwable) -> Unit) {
    private val page = RenderNode("QDHelper.Glass.Page")
    private val tabs = RenderNode("QDHelper.Glass.NativeTabs")
    private val rawTabs = RenderNode("QDHelper.Glass.RawNativeTabs")
    private val tabHighlight = GlassTabHighlight()
    private var tabContent: GlassTabContent? = null
    private val base = RenderNode("QDHelper.Glass.Base")
    private val embedded = RenderNode("QDHelper.Glass.Droplet")
    private val clear = RenderNode("QDHelper.Glass.ClearDroplet")
    private val shadow = RenderNode("QDHelper.Glass.Shadow")
    private val inner = RenderNode("QDHelper.Glass.InnerShadow")
    private val nodes = listOf(page, tabs, rawTabs, base, embedded, clear, shadow, inner)
    private val sourceLocation = IntArray(2)
    private val tabsLocation = IntArray(2)
    private val bounds = RectF()
    private val selection = RectF()
    private val sampleBounds = RectF()
    private val outline = Path()
    private val droplet = Path()
    private val embeddedOutline = Path()
    private val backdropTransform = Matrix()
    private val surface = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wash = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val erase = Paint(Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.CLEAR }
    private val plus = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; blendMode = BlendMode.PLUS }
    private val bloomPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.PLUS }
    private var lens: RuntimeShader? = null
    private var dropletLens: RuntimeShader? = null
    private var bloom: RuntimeShader? = null
    private var lighting: GlassLighting? = null
    private var dropletLighting: GlassLighting? = null
    private var capture: GlassSourceCapture? = null
    private var blurEffect: RenderEffect? = null
    private var saturation: RenderEffect? = null
    private var lightAngle = GlassTilt.REST_ANGLE
    private var width = 0
    private var height = 0
    private var margin = 0
    private var density = 1f
    private var night = false
    private var spec = GlassEffectsSpec()
    private var pressure = 0f
    private var highlightProgress = 0f
    private var touchX = 0f
    private var touchY = 0f
    private var selectionVisible = false
    private var selectionScaleX = 1f
    private var selectionScaleY = 1f
    private var selectionContentReady = false
    private var lowX = 0.5f
    private var lowY = 0.5f
    private var highX = 0.5f
    private var highY = 0.5f
    private var baseDirty = true
    private var selectionDirty = true
    private var shadowEffect: RenderEffect? = null
    private var innerEffect: RenderEffect? = null
    private var innerBlur = Float.NaN
    private var ready = false
    private var broken = false

    fun configure(width: Int, height: Int, density: Float, spec: GlassEffectsSpec, night: Boolean) {
        if (this.width == width && this.height == height && this.density == density && this.spec == spec && this.night == night) return
        if (this.width != width || this.height != height || this.density != density) ready = false
        this.width = width
        this.height = height
        this.density = density
        this.spec = spec
        this.night = night
        margin = ceil(64f * density).toInt()
        blurEffect = null
        shadowEffect = null
        innerEffect = null
        lighting = null
        dropletLighting = null
        baseDirty = true
        selectionDirty = true
    }

    fun setPress(value: Float, x: Float, y: Float, highlight: Float) {
        val next = if (spec.press) value.coerceIn(0f, 1f) else 0f
        val nextHighlight = if (spec.press) highlight.coerceIn(0f, 1f) else 0f
        if (pressure == next && touchX == x && touchY == y && highlightProgress == nextHighlight) return
        if (pressure != next) baseDirty = true
        pressure = next
        highlightProgress = nextHighlight
        touchX = x
        touchY = y
        selectionDirty = true
    }

    fun setLightAngle(angle: Float) {
        if (lightAngle == angle) return
        lightAngle = angle
    }

    fun setTabContent(content: GlassTabContent?) {
        if (tabContent == content) return
        tabContent = content
        content?.let(tabHighlight::update)
        selectionDirty = true
    }

    fun setSelection(rect: RectF, visible: Boolean, scaleX: Float, scaleY: Float) {
        val nextScaleX = scaleX.takeIf(Float::isFinite)?.coerceIn(0.5f, 2f) ?: 1f
        val nextScaleY = scaleY.takeIf(Float::isFinite)?.coerceIn(0.5f, 2f) ?: 1f
        if (selection == rect && selectionVisible == visible &&
            selectionScaleX == nextScaleX && selectionScaleY == nextScaleY
        ) return
        selection.set(rect)
        selectionVisible = visible
        selectionScaleX = nextScaleX
        selectionScaleY = nextScaleY
        selectionDirty = true
    }

    /** Re-record only the bounded band, on an existing host frame. No bitmap readback or idle loop. */
    fun prepare(source: View, screenX: Int, screenY: Int, selectionContent: View? = null): Boolean {
        if (broken || width <= 0 || height <= 0 || !source.isAttachedToWindow) return false
        return guarded {
            source.getLocationOnScreen(sourceLocation)
            val dx = (screenX - sourceLocation[0]).toFloat()
            val dy = (screenY - sourceLocation[1]).toFloat()
            val captureWidth = width + 2 * margin
            val captureHeight = height + 2 * margin
            val lx = max(0.5f, margin - dx + 0.5f).coerceAtMost(captureWidth - 0.5f)
            val ly = max(0.5f, margin - dy + 0.5f).coerceAtMost(captureHeight - 0.5f)
            val hx = min(captureWidth - 0.5f, margin - dx + source.width - 0.5f).coerceAtLeast(lx)
            val hy = min(captureHeight - 0.5f, margin - dy + source.height - 0.5f).coerceAtLeast(ly)
            if (lx != lowX || ly != lowY || hx != highX || hy != highY) { baseDirty = true; selectionDirty = true }
            lowX = lx; lowY = ly; highX = hx; highY = hy
            record(page) { recording ->
                recording.drawColor(if (night) 0xFF111111.toInt() else 0xFFF7F7F7.toInt())
                recording.translate(margin - dx, margin - dy)
                recording.clipRect(0, 0, source.width, source.height)
                (capture ?: GlassSourceCapture(source.context).also { capture = it }).record(recording, source)
            }
            captureTabs(selectionContent, screenX, screenY)
            ready = true
        }
    }

    private fun captureTabs(content: View?, screenX: Int, screenY: Int) {
        val canCapture = selectionVisible && (pressure > MIN_PRESS || tabContent != null) && content != null &&
            content.isAttachedToWindow && content.isShown && content.width > 0 && content.height > 0
        if (selectionContentReady != canCapture) {
            selectionContentReady = canCapture
            selectionDirty = true
        }
        if (!canCapture) {
            tabs.discardDisplayList()
            rawTabs.discardDisplayList()
            return
        }
        requireNotNull(content).getLocationOnScreen(tabsLocation)
        record(rawTabs) { recording ->
            recording.translate(margin + (tabsLocation[0] - screenX).toFloat(), margin + (tabsLocation[1] - screenY).toFloat())
            requireNotNull(capture).record(recording, content)
        }
        if (tabContent != null) tabHighlight.captureIcons(width, height, margin) { drawNode(it, rawTabs) }
        record(tabs) { recording ->
            recording.translate(margin.toFloat(), margin.toFloat())
            if (tabContent != null) tabHighlight.drawAccent(recording, pressure) { drawNode(it, rawTabs) }
            else drawNode(recording, rawTabs)
        }
    }

    /** Material changes can reuse a frozen page when a non-dimming popup temporarily takes focus. */
    fun draw(canvas: Canvas): Boolean {
        if (!ready || broken || !canvas.isHardwareAccelerated) return false
        return guarded {
            ensureMaterials()
            if (spec.shadow) drawNode(canvas, shadow)
            drawBase(canvas, includeLighting = true)
            if (selectionVisible && !hasForegroundSelection()) drawDroplet(canvas)
        }
    }

    /** Neutral native row, with the accent row revealed only inside the final lens. */
    fun drawForeground(canvas: Canvas): Boolean {
        if (!ready || broken || !canvas.isHardwareAccelerated || !hasForegroundSelection()) return false
        return guarded {
            ensureMaterials()
            if (tabContent != null) {
                // Cover the live host's selection colors with the same captured native ink.
                // This layer is draw-only; actual host views still own input/accessibility.
                drawBase(canvas, includeLighting = true)
                val save = canvas.save()
                canvas.clipPath(outline)
                tabHighlight.drawNeutral(canvas) { drawNode(it, rawTabs) }
                canvas.restoreToCount(save)
            }
            drawDroplet(canvas)
        }
    }

    private fun hasForegroundSelection() = selectionVisible && selectionContentReady && (pressure > MIN_PRESS || tabContent != null)

    /** A foreground transition must refresh draw commands even when geometry did not change. */
    fun invalidateMaterials() {
        baseDirty = true
        selectionDirty = true
    }

    private fun ensureMaterials() {
        // HWUI can discard display lists while the Activity is stopped. A cache miss must
        // invalidate the parameter cache too: updateMaterials otherwise skips recording base.
        if (!base.hasDisplayList() || (spec.shadow && !shadow.hasDisplayList())) baseDirty = true
        if (selectionVisible && (pressure > MIN_PRESS || hasForegroundSelection()) &&
            (!embedded.hasDisplayList() || !clear.hasDisplayList() || (spec.shadow && pressure > MIN_PRESS && !inner.hasDisplayList()))) {
            selectionDirty = true
        }
        if (baseDirty || selectionDirty) updateMaterials()
    }

    private fun updateMaterials() {
        if (baseDirty) {
            val growth = if (spec.press && width > 0) 16f * density / width * pressure else 0f
            bounds.set(-width * growth / 2f, -height * growth / 2f, width * (1f + growth / 2f), height * (1f + growth / 2f))
            path(outline, bounds)
            surface.color = if (night) 0x662C2C2E else 0x66F2F2F7
            val vibrancy = saturation ?: RenderEffect.createColorFilterEffect(
                ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.5f) }),
            ).also { saturation = it }
            var effect = if (spec.blur) {
                blurEffect ?: RenderEffect.createBlurEffect(4f * density, 4f * density, vibrancy, Shader.TileMode.CLAMP)
                    .also { blurEffect = it }
            } else vibrancy
            if (spec.refraction) {
                val shader = lens ?: RuntimeShader(GLASS_LENS_AGSL).also { lens = it }
                effect = RenderEffect.createChainEffect(optics(shader, bounds, 24f * density, -24f * density, false), effect)
            }
            base.setRenderEffect(effect)
            record(base) { it.drawRenderNode(page) }
            if (spec.shadow) {
                shade.color = if (night) 0x33000000 else 0x1A000000
                record(shadow) { it.translate(margin.toFloat(), margin.toFloat()); it.drawPath(outline, shade) }
                shadow.setRenderEffect(shadowEffect ?: RenderEffect.createBlurEffect(10f * density, 10f * density, Shader.TileMode.DECAL).also { shadowEffect = it })
            } else shadow.discardDisplayList()
        }
        path(droplet, selection)
        // The lens stretches, but its backdrop stays in bar coordinates. This is the inverse
        // transform used by KernelSU's LayerBackdrop and ForbidAd4TieBa's DropletPanel.
        backdropTransform.setScale(1f / selectionScaleX, 1f / selectionScaleY, selection.centerX(), selection.centerY())
        outline.transform(backdropTransform, embeddedOutline)
        if (selectionVisible && (pressure > MIN_PRESS || hasForegroundSelection())) {
            recordDroplet(embedded, includePill = true)
            recordDroplet(clear, includePill = false)
            val dropletEffect = if (spec.refraction && pressure > MIN_PRESS) {
                val shader = dropletLens ?: RuntimeShader(GLASS_LENS_AGSL).also { dropletLens = it }
                optics(shader, selection, 10f * density * pressure, -14f * density * pressure, true)
            } else null
            embedded.setRenderEffect(dropletEffect)
            clear.setRenderEffect(dropletEffect)
            if (spec.shadow && pressure > MIN_PRESS) {
                val blur = 8f * density * pressure
                shade.color = 0x26000000
                record(inner) {
                    it.translate(margin.toFloat(), margin.toFloat())
                    it.clipPath(droplet)
                    it.drawPath(droplet, shade)
                    it.translate(0f, blur)
                    it.drawPath(droplet, erase)
                }
                if (innerBlur != blur || innerEffect == null) {
                    innerBlur = blur
                    innerEffect = RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.DECAL)
                }
                inner.setRenderEffect(innerEffect)
                inner.alpha = pressure
            } else inner.discardDisplayList()
        } else {
            for (node in listOf(embedded, clear, inner)) {
                node.setRenderEffect(null)
                node.discardDisplayList()
            }
        }
        baseDirty = false
        selectionDirty = false
    }

    private fun optics(shader: RuntimeShader, rect: RectF, bevel: Float, amount: Float, depth: Boolean): RenderEffect {
        shader.setFloatUniform("size", max(1f, rect.width()), max(1f, rect.height()))
        shader.setFloatUniform("offset", -margin - rect.left, -margin - rect.top)
        shader.setFloatUniform("radius", min(rect.width(), rect.height()) / 2f)
        shader.setFloatUniform("bevel", bevel)
        shader.setFloatUniform("refraction", amount)
        shader.setFloatUniform("depth", if (depth) 1f else 0f)
        shader.setFloatUniform("dispersion", if (depth) 0.5f else 0f)
        if (depth && selectionContentReady) {
            // The combined band is opaque even beyond the page's bounds. Clamping it to the
            // page alone would smear/cut native labels when they extend into its bottom inset.
            sampleBounds.set(0.5f - margin, 0.5f - margin, width + margin - 0.5f, height + margin - 0.5f)
        } else {
            sampleBounds.set(lowX - margin, lowY - margin, highX - margin, highY - margin)
        }
        // Clamp in the same compensated space as the recorded content, including edge tabs.
        if (depth) backdropTransform.mapRect(sampleBounds)
        shader.setFloatUniform("sampleLo", sampleBounds.left + margin, sampleBounds.top + margin)
        shader.setFloatUniform("sampleHi", sampleBounds.right + margin, sampleBounds.bottom + margin)
        return RenderEffect.createRuntimeShaderEffect(shader, "content")
    }

    private fun recordDroplet(node: RenderNode, includePill: Boolean) = record(node) {
        it.translate(margin.toFloat(), margin.toFloat())
        it.withGlassLensTransform(selection, selectionScaleX, selectionScaleY, inverse = true) {
            drawNode(it, page)
            if (includePill) drawBase(it, includeLighting = false)
        }
        drawWash(it)
        if (selectionContentReady) {
            it.withGlassLensTransform(selection, selectionScaleX, selectionScaleY, inverse = true) {
                drawNode(it, tabs)
            }
        }
    }

    private fun drawBase(canvas: Canvas, includeLighting: Boolean) {
        val save = canvas.save()
        canvas.clipPath(outline)
        drawNode(canvas, base)
        canvas.drawPath(outline, surface)
        if (spec.highlight && highlightProgress > 0.001f) {
            plus.alpha = (15f * highlightProgress).roundToInt()
            canvas.drawPath(outline, plus)
            val shader = bloom ?: RuntimeShader(GLASS_BLOOM_AGSL).also { bloom = it }
            shader.setFloatUniform("alpha", 0.12f * highlightProgress)
            shader.setFloatUniform("radius", min(width, height) * 1.2f)
            shader.setFloatUniform("position", touchX.coerceIn(0f, width.toFloat()), touchY.coerceIn(0f, height.toFloat()))
            bloomPaint.shader = shader
            canvas.drawPath(outline, bloomPaint)
        }
        canvas.restoreToCount(save)
        if (includeLighting && spec.highlight) {
            val light = lighting ?: GlassLighting(density, (-PI / 4).toFloat()).also { lighting = it }
            drawLighting(canvas, light, bounds, 0.75f)
        }
    }

    private fun drawWash(canvas: Canvas) {
        wash.color = if (night) Color.WHITE else Color.BLACK
        wash.alpha = (26f * (1f - pressure)).roundToInt()
        canvas.drawPath(droplet, wash)
        wash.color = Color.BLACK
        wash.alpha = (8f * pressure).roundToInt()
        canvas.drawPath(droplet, wash)
    }

    private fun drawDroplet(canvas: Canvas) {
        canvas.withGlassLensTransform(selection, selectionScaleX, selectionScaleY) {
            if (pressure <= MIN_PRESS && !hasForegroundSelection()) {
                drawWash(canvas)
            } else {
                val save = canvas.save()
                canvas.clipPath(droplet)
                val overflow = canvas.save()
                canvas.clipOutPath(embeddedOutline)
                drawNode(canvas, clear)
                canvas.restoreToCount(overflow)
                val overlap = canvas.save()
                canvas.clipPath(embeddedOutline)
                drawNode(canvas, embedded)
                canvas.restoreToCount(overlap)
                if (spec.shadow && inner.hasDisplayList()) drawNode(canvas, inner)
                canvas.restoreToCount(save)
                if (spec.highlight) {
                    val light = dropletLighting ?: GlassLighting(density, (PI / 2).toFloat()).also { dropletLighting = it }
                    drawLighting(canvas, light, selection, pressure)
                }
            }
        }
    }

    private fun drawLighting(canvas: Canvas, light: GlassLighting, rect: RectF, alpha: Float) {
        val save = canvas.save()
        canvas.translate(rect.left, rect.top)
        light.draw(canvas, rect.width().roundToInt(), rect.height().roundToInt(), lightAngle, alpha)
        canvas.restoreToCount(save)
    }

    private fun path(path: Path, rect: RectF) {
        path.reset()
        if (!rect.isEmpty) {
            val radius = min(rect.width(), rect.height()) / 2f
            path.addRoundRect(rect, radius, radius, Path.Direction.CW)
        }
    }

    private fun record(node: RenderNode, draw: (Canvas) -> Unit) {
        val w = width + 2 * margin
        val h = height + 2 * margin
        node.setPosition(0, 0, w, h)
        val canvas = node.beginRecording(w, h)
        try { draw(canvas) } finally { node.endRecording() }
    }

    private fun drawNode(canvas: Canvas, node: RenderNode) {
        val save = canvas.save()
        canvas.translate(-margin.toFloat(), -margin.toFloat())
        canvas.drawRenderNode(node)
        canvas.restoreToCount(save)
    }

    private inline fun guarded(action: () -> Unit): Boolean = try {
        action()
        true
    } catch (error: Exception) {
        broken = true
        release()
        failure(error)
        false
    }

    fun release() {
        ready = false
        selectionContentReady = false
        nodes.forEach { it.setRenderEffect(null); it.discardDisplayList() }
        tabHighlight.release()
        lens = null; dropletLens = null; bloom = null
        lighting = null; dropletLighting = null; capture = null
        blurEffect = null; saturation = null; shadowEffect = null; innerEffect = null
        baseDirty = true
        selectionDirty = true
    }

    private companion object {
        const val MIN_PRESS = 0.001f
    }
}

/** Stretch the lens and its lighting; inverse backdrop sampling preserves the native content's proportions. */
internal inline fun Canvas.withGlassLensTransform(
    rect: RectF,
    scaleX: Float,
    scaleY: Float,
    inverse: Boolean = false,
    draw: () -> Unit,
) {
    val save = save()
    try {
        scale(if (inverse) 1f / scaleX else scaleX, if (inverse) 1f / scaleY else scaleY, rect.centerX(), rect.centerY())
        draw()
    } finally {
        restoreToCount(save)
    }
}
