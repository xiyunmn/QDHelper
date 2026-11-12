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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * ForbidAd4TieBa's page -> vibrancy -> blur -> lens -> surface stack, adapted for Qidian.
 * The raised droplet has separate clear/embedded materials, clipped AFTER refraction so the
 * pill's blur cannot bleed outside its outline. KernelSU's combined backdrop is built from the
 * page and independent material/content display lists; badges stay above refraction.
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
    private val material = RenderNode("QDHelper.Glass.TabMaterial")
    private val refractedTabs = RenderNode("QDHelper.Glass.RefractedTabs")
    private val embedded = RenderNode("QDHelper.Glass.Droplet")
    private val clear = RenderNode("QDHelper.Glass.ClearDroplet")
    private val shadow = RenderNode("QDHelper.Glass.Shadow")
    private val selectionShadow = RenderNode("QDHelper.Glass.SelectionShadow")
    private val inner = RenderNode("QDHelper.Glass.InnerShadow")
    private val nodes = listOf(page, tabs, rawTabs, base, material, refractedTabs, embedded, clear, shadow, selectionShadow, inner)
    private val sourceLocation = IntArray(2)
    private val tabsLocation = IntArray(2)
    private val bounds = RectF()
    private val selection = RectF()
    private val controlBounds = RectF()
    private val lensBounds: RectF get() = selection
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
    private var materialLens: RuntimeShader? = null
    private var contentLens: RuntimeShader? = null
    private var bloom: RuntimeShader? = null
    private var lighting: GlassLighting? = null
    private var dropletLighting: GlassLighting? = null
    private var capture: GlassSourceCapture? = null
    private var blurEffect: RenderEffect? = null
    private var blurRadius = Float.NaN
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
    private var selectionSkew = 0f
    private var selectionContentReady = false
    private var lowX = 0.5f
    private var lowY = 0.5f
    private var highX = 0.5f
    private var highY = 0.5f
    private var baseDirty = true
    private var selectionDirty = true
    private var shadowEffect: RenderEffect? = null
    private var shadowRadius = Float.NaN
    private var selectionShadowEffect: RenderEffect? = null
    private var innerEffect: RenderEffect? = null
    private var innerBlur = Float.NaN
    private var ready = false
    private var broken = false

    fun configure(width: Int, height: Int, density: Float, spec: GlassEffectsSpec, night: Boolean, margin: Int) {
        if (this.width == width && this.height == height && this.density == density && this.spec == spec && this.night == night && this.margin == margin) return
        if (this.width != width || this.height != height || this.density != density || this.margin != margin) ready = false
        this.width = width
        this.height = height
        this.density = density
        this.spec = spec
        this.night = night
        this.margin = margin
        blurEffect = null
        shadowEffect = null
        selectionShadowEffect = null
        innerEffect = null
        lighting = null
        dropletLighting = null
        saturation = null
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
        if ((tabContent == null) != (content == null)) {
            baseDirty = true
            shadowEffect = null
        }
        tabContent = content
        content?.let(tabHighlight::update)
        selectionDirty = true
    }

    fun setControlBounds(rect: RectF?) {
        if ((rect == null && controlBounds.isEmpty) || rect == controlBounds) return
        if (rect == null) controlBounds.setEmpty() else controlBounds.set(rect)
        baseDirty = true
        selectionDirty = true
    }

    fun setSelection(rect: RectF, visible: Boolean, scaleX: Float, scaleY: Float, skew: Float) {
        val nextScaleX = scaleX.takeIf(Float::isFinite)?.coerceIn(0.5f, GlassBarMotion.MAX_SCALE_X) ?: 1f
        val nextScaleY = scaleY.takeIf(Float::isFinite)?.coerceIn(0.5f, GlassBarMotion.MAX_SCALE_Y) ?: 1f
        val nextSkew = skew.takeIf(Float::isFinite)?.coerceIn(-GlassBarMotion.MAX_SKEW, GlassBarMotion.MAX_SKEW) ?: 0f
        if (selection == rect && selectionVisible == visible &&
            selectionScaleX == nextScaleX && selectionScaleY == nextScaleY && selectionSkew == nextSkew
        ) return
        if (selectionVisible != visible) baseDirty = true
        selection.set(rect)
        selectionVisible = visible
        selectionScaleX = nextScaleX
        selectionScaleY = nextScaleY
        selectionSkew = nextSkew
        selectionDirty = true
    }

    /** Re-record only the bounded band, on an existing host frame. No bitmap readback or idle loop. */
    fun prepare(source: View, screenX: Int, screenY: Int, selectionContent: View? = null): Boolean {
        if (broken || width <= 0 || height <= 0) return false
        val prepared = guarded {
            if (source.isAttachedToWindow && source.isShown && source.width > 0 && source.height > 0) {
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
                ready = true
            }
            // A hidden page freezes its last display list; visible native tabs keep updating.
            captureTabs(selectionContent, screenX, screenY)
        }
        return prepared && ready && page.hasDisplayList()
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
            (capture ?: GlassSourceCapture(content.context).also { capture = it }).record(recording, content)
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
            if (!hasForegroundCover()) drawBase(canvas, includeLighting = true)
            if (hasOpticalSelection() && !hasForegroundSelection()) drawDroplet(canvas)
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

    private fun hasOpticalSelection() = selectionVisible

    private fun hasForegroundCover() = tabContent != null && hasForegroundSelection()

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
        if (tabContent != null && !material.hasDisplayList()) baseDirty = true
        if (hasOpticalSelection() && (spec.refraction || pressure > MIN_PRESS || hasForegroundSelection()) &&
            (!embedded.hasDisplayList() || !clear.hasDisplayList() ||
                (tabContent != null && selectionContentReady && !refractedTabs.hasDisplayList()) ||
                (spec.shadow && pressure > MIN_PRESS && !inner.hasDisplayList()))) {
            selectionDirty = true
        }
        if (baseDirty || selectionDirty) updateMaterials()
    }

    private fun updateMaterials() {
        if (baseDirty) {
            val actionPress = if (!selectionVisible) pressure else 0f
            val growth = if (spec.press && selectionVisible && width > 0) 16f * density / width * pressure else 0f
            val horizontalInset = width * (0.015f * actionPress - growth) / 2f
            val verticalInset = height * (0.06f * actionPress - growth) / 2f
            bounds.set(horizontalInset, verticalInset, width - horizontalInset, height - verticalInset)
            path(outline, bounds)
            surface.color = if (night) 0x66292A2D else 0x66F5F5FA
            surface.alpha = (255f * spec.surfaceOpacity *
                (if (tabContent != null) 1f else 1f - 6f / 102f * pressure)).roundToInt().coerceIn(0, 255)
            val vibrancy = saturation ?: RenderEffect.createColorFilterEffect(
                ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(if (night) 1.25f else 1.35f) }),
            ).also { saturation = it }
            var effect = if (spec.blur) {
                val radius = spec.blurRadiusDp * density * (1f - 0.35f * actionPress)
                if (blurEffect == null || blurRadius != radius) {
                    blurRadius = radius
                    blurEffect = RenderEffect.createBlurEffect(radius, radius, vibrancy, Shader.TileMode.CLAMP)
                }
                requireNotNull(blurEffect)
            } else vibrancy
            if (spec.refraction) {
                val shader = lens ?: RuntimeShader(GLASS_LENS_AGSL).also { lens = it }
                val height = if (tabContent != null) 16f else 24f * (1f + 0.12f * actionPress)
                val amount = if (tabContent != null) 32f else 24f * (1f + 0.25f * actionPress)
                effect = RenderEffect.createChainEffect(optics(shader, bounds, height * density * spec.refractionScale,
                    -amount * density * spec.refractionScale, compensated = false), effect)
            }
            base.setRenderEffect(effect)
            record(base) { it.drawRenderNode(page) }
            if (tabContent != null) {
                var materialEffect: RenderEffect = blurEffect ?: vibrancy
                if (spec.refraction && pressure > MIN_PRESS) {
                    val shader = materialLens ?: RuntimeShader(GLASS_LENS_AGSL).also { materialLens = it }
                    materialEffect = RenderEffect.createChainEffect(optics(shader, bounds,
                        16f * density * pressure * spec.refractionScale,
                        -32f * density * pressure * spec.refractionScale, compensated = false), materialEffect)
                }
                material.setRenderEffect(materialEffect)
                record(material) { it.drawRenderNode(page) }
            }
            if (spec.shadow) {
                shade.color = if (tabContent != null) 0x1A000000 else if (night) 0x33000000 else 0x1A000000
                shade.alpha = (shade.alpha * spec.shadowScale * (1f - 0.15f * actionPress)).roundToInt().coerceIn(0, 255)
                val offset = if (tabContent != null) 4f * density else 0f
                val blur = if (tabContent != null) 24f * density else 10f * density * (1f - 0.35f * actionPress)
                record(shadow) { it.translate(margin.toFloat(), margin + offset); it.drawPath(outline, shade) }
                if (shadowEffect == null || shadowRadius != blur) {
                    shadowRadius = blur
                    shadowEffect = RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.DECAL)
                }
                shadow.setRenderEffect(shadowEffect)
            } else shadow.discardDisplayList()
        }
        path(droplet, lensBounds)
        // The lens stretches, but its backdrop stays in bar coordinates. This is the inverse
        // transform used by KernelSU's LayerBackdrop and ForbidAd4TieBa's DropletPanel.
        backdropTransform.setScale(1f / selectionScaleX, 1f / selectionScaleY, lensBounds.centerX(), lensBounds.centerY())
        // M^-1 = T(center) * S^-1 * K^-1 * T(-center), matching Canvas below.
        backdropTransform.preSkew(-selectionSkew, 0f, lensBounds.centerX(), lensBounds.centerY())
        outline.transform(backdropTransform, embeddedOutline)
        if (hasOpticalSelection() && (spec.refraction || pressure > MIN_PRESS || hasForegroundSelection())) {
            recordDroplet(embedded, includePill = true)
            recordDroplet(clear, includePill = false)
            val dropletEffect = if (spec.refraction) {
                val shader = dropletLens ?: RuntimeShader(GLASS_LENS_AGSL).also { dropletLens = it }
                optics(shader, lensBounds, 7f * density * spec.refractionScale,
                    -9.8f * density * spec.refractionScale, compensated = true, dispersion = 1f)
            } else null
            val restingBlur = if (tabContent != null) spec.blurRadiusDp * density * (1f - pressure) else 0f
            val backgroundEffect = if (restingBlur > MIN_PRESS) {
                val blur = RenderEffect.createBlurEffect(restingBlur, restingBlur, Shader.TileMode.CLAMP)
                if (dropletEffect != null) RenderEffect.createChainEffect(dropletEffect, blur) else blur
            } else dropletEffect
            embedded.setRenderEffect(backgroundEffect)
            clear.setRenderEffect(backgroundEffect)
            if (selectionContentReady && tabContent != null) {
                record(refractedTabs) {
                    it.translate(margin.toFloat(), margin.toFloat())
                    it.withGlassLensTransform(lensBounds, selectionScaleX, selectionScaleY, selectionSkew, inverse = true) {
                        drawNode(it, tabs)
                    }
                }
                refractedTabs.setRenderEffect(if (spec.refraction && pressure > MIN_PRESS) {
                    val shader = contentLens ?: RuntimeShader(GLASS_LENS_AGSL).also { contentLens = it }
                    optics(shader, lensBounds, 7f * density * pressure * spec.refractionScale,
                        -9.8f * density * pressure * spec.refractionScale, compensated = true, dispersion = 1f)
                } else null)
            } else refractedTabs.discardDisplayList()
            if (spec.shadow && pressure > MIN_PRESS) {
                val blur = 8f * density * pressure
                shade.color = 0x26000000
                shade.alpha = (shade.alpha * spec.shadowScale).roundToInt().coerceIn(0, 255)
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
                if (tabContent != null) {
                    shade.color = 0x1A000000
                    shade.alpha = (shade.alpha * spec.shadowScale).roundToInt().coerceIn(0, 255)
                    record(selectionShadow) {
                        it.translate(margin.toFloat(), margin + 4f * density)
                        it.drawPath(droplet, shade)
                    }
                    selectionShadow.setRenderEffect(selectionShadowEffect ?: RenderEffect.createBlurEffect(
                        24f * density, 24f * density, Shader.TileMode.DECAL).also { selectionShadowEffect = it })
                    selectionShadow.alpha = pressure
                } else selectionShadow.discardDisplayList()
            } else {
                inner.discardDisplayList()
                selectionShadow.discardDisplayList()
            }
        } else {
            for (node in listOf(embedded, clear, inner, refractedTabs, selectionShadow)) {
                node.setRenderEffect(null)
                node.discardDisplayList()
            }
        }
        baseDirty = false
        selectionDirty = false
    }

    private fun optics(shader: RuntimeShader, rect: RectF, bevel: Float, amount: Float,
        compensated: Boolean, dispersion: Float = 0f,
    ): RenderEffect {
        shader.setFloatUniform("size", max(1f, rect.width()), max(1f, rect.height()))
        shader.setFloatUniform("offset", -margin - rect.left, -margin - rect.top)
        shader.setFloatUniform("radius", min(rect.width(), rect.height()) / 2f)
        shader.setFloatUniform("bevel", bevel)
        shader.setFloatUniform("refraction", amount)
        shader.setFloatUniform("depth", 0f)
        shader.setFloatUniform("dispersion", dispersion)
        if (compensated && selectionContentReady) {
            // The combined band is opaque even beyond the page's bounds. Clamping it to the
            // page alone would smear/cut native labels when they extend into its bottom inset.
            sampleBounds.set(0.5f - margin, 0.5f - margin, width + margin - 0.5f, height + margin - 0.5f)
        } else {
            sampleBounds.set(lowX - margin, lowY - margin, highX - margin, highY - margin)
        }
        // Clamp in the same compensated space as the recorded content, including edge tabs.
        if (compensated) backdropTransform.mapRect(sampleBounds)
        shader.setFloatUniform("sampleLo", sampleBounds.left + margin, sampleBounds.top + margin)
        shader.setFloatUniform("sampleHi", sampleBounds.right + margin, sampleBounds.bottom + margin)
        return RenderEffect.createRuntimeShaderEffect(shader, "content")
    }

    private fun recordDroplet(node: RenderNode, includePill: Boolean) = record(node) {
        it.translate(margin.toFloat(), margin.toFloat())
        it.withGlassLensTransform(lensBounds, selectionScaleX, selectionScaleY, selectionSkew, inverse = true) {
            drawNode(it, page)
            if (includePill) {
                if (tabContent != null) {
                    val save = it.save()
                    it.clipPath(outline)
                    drawNode(it, material)
                    it.drawPath(outline, surface)
                    it.restoreToCount(save)
                } else drawBase(it, includeLighting = false)
            }
        }
    }

    private fun drawBase(canvas: Canvas, includeLighting: Boolean) {
        val save = canvas.save()
        canvas.clipPath(outline)
        drawNode(canvas, base)
        canvas.drawPath(outline, surface)
        if (selectionVisible && tabContent == null && spec.highlight && highlightProgress > 0.001f) {
            plus.alpha = (15f * highlightProgress * spec.highlightOpacity / 0.75f).roundToInt().coerceIn(0, 255)
            canvas.drawPath(outline, plus)
            val shader = bloom ?: RuntimeShader(GLASS_BLOOM_AGSL).also { bloom = it }
            shader.setFloatUniform("alpha", 0.12f * highlightProgress * spec.highlightOpacity / 0.75f)
            val radius = min(width, height) * 1.2f
            shader.setFloatUniform("radius", max(1f, radius))
            shader.setFloatUniform("position", touchX.coerceIn(0f, width.toFloat()), touchY.coerceIn(0f, height.toFloat()))
            bloomPaint.shader = shader
            canvas.drawPath(outline, bloomPaint)
        }
        canvas.restoreToCount(save)
        if (includeLighting && spec.highlight) {
            val light = lighting ?: GlassLighting(density, (-PI / 4).toFloat()).also { lighting = it }
            // Action bars brighten their existing rim, without adding a movable interior fill.
            drawLighting(canvas, light, bounds, if (tabContent != null) spec.highlightOpacity / 0.75f else spec.highlightOpacity,
                pressProgress = if (selectionVisible) 0f else highlightProgress)
        }
    }

    private fun drawWash(canvas: Canvas) {
        val visibility = if (selectionVisible) 1f else pressure
        wash.color = if (night) Color.WHITE else Color.BLACK
        wash.alpha = (26f * (1f - pressure) * visibility).roundToInt()
        canvas.drawPath(droplet, wash)
        if (tabContent != null) return
        wash.color = Color.BLACK
        wash.alpha = (8f * pressure * visibility).roundToInt()
        canvas.drawPath(droplet, wash)
    }

    private fun drawDroplet(canvas: Canvas) {
        canvas.withGlassLensTransform(lensBounds, selectionScaleX, selectionScaleY, selectionSkew) {
            if (spec.shadow && selectionShadow.hasDisplayList()) drawNode(canvas, selectionShadow)
            if (!spec.refraction && pressure <= MIN_PRESS && !hasForegroundSelection()) {
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
                drawWash(canvas)
                if (selectionContentReady && tabContent != null) drawNode(canvas, refractedTabs)
                // Badges preserve native color and coverage above the two optical inputs.
                if (selectionContentReady) {
                    canvas.withGlassLensTransform(lensBounds, selectionScaleX, selectionScaleY, selectionSkew, inverse = true) {
                        if (tabContent != null) tabHighlight.drawBadges(canvas) { drawNode(it, rawTabs) }
                        else drawNode(canvas, tabs)
                    }
                }
                if (spec.shadow && inner.hasDisplayList()) drawNode(canvas, inner)
                canvas.restoreToCount(save)
                if (spec.highlight) {
                    val light = dropletLighting ?: GlassLighting(density, (PI / 2).toFloat()).also { dropletLighting = it }
                    drawLighting(canvas, light, lensBounds, pressure * spec.highlightOpacity / 0.75f, selectionSkew * 1.8f)
                }
            }
        }
    }

    private fun drawLighting(canvas: Canvas, light: GlassLighting, rect: RectF, alpha: Float, motionAngle: Float = 0f, pressProgress: Float = 0f) {
        val save = canvas.save()
        canvas.translate(rect.left, rect.top)
        if (tabContent != null) light.drawTab(canvas, rect.width().roundToInt(), rect.height().roundToInt(),
            lightAngle - GlassTilt.REST_ANGLE + (PI / 4).toFloat() + motionAngle, alpha)
        else light.draw(canvas, rect.width().roundToInt(), rect.height().roundToInt(), lightAngle + motionAngle, alpha,
            touchX - rect.left, touchY - rect.top, pressProgress)
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
        lens = null; dropletLens = null; materialLens = null; contentLens = null; bloom = null
        lighting = null; dropletLighting = null; capture = null
        blurEffect = null; saturation = null; shadowEffect = null; selectionShadowEffect = null; innerEffect = null
        baseDirty = true
        selectionDirty = true
    }

    private companion object {
        const val MIN_PRESS = 0.001f
    }
}

/** Shear and stretch the lens; the exact inverse keeps backdrop and native ink in bar coordinates. */
internal inline fun Canvas.withGlassLensTransform(
    rect: RectF,
    scaleX: Float,
    scaleY: Float,
    skew: Float,
    inverse: Boolean = false,
    draw: () -> Unit,
) {
    val save = save()
    try {
        translate(rect.centerX(), rect.centerY())
        if (inverse) {
            scale(1f / scaleX, 1f / scaleY)
            this.skew(-skew, 0f)
        } else {
            this.skew(skew, 0f)
            scale(scaleX, scaleY)
        }
        translate(-rect.centerX(), -rect.centerY())
        draw()
    } finally {
        restoreToCount(save)
    }
}
