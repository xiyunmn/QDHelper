package com.xiyunmn.qdhelper.shared.ui.glass

import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader

/** Geometry in bar coordinates. Only icon/label ink is tinted; badges keep their own colors. */
data class GlassTabContent(
    val tabs: List<RectF>,
    val tintAreas: List<RectF>,
    val protectedAreas: List<RectF>,
    val neutralColor: Int,
    val accentColor: Int,
    val iconAreas: List<RectF> = emptyList(),
    val selectedTab: RectF? = null,
    val night: Boolean = false,
    val selectedPreviews: List<GlassTabIconPreview> = emptyList(),
)

/** Independently recorded native selected artwork, positioned in bar coordinates. */
data class GlassTabIconPreview(val bounds: RectF, val node: RenderNode)

/** KernelSU's neutral row + accent row, revealed continuously by the selection lens. */
@android.annotation.TargetApi(33)
internal class GlassTabHighlight {
    private val tint = Path()
    private val protected = Path()
    private val icons = Path()
    private val allInk = Path()
    private val previewArea = Path()
    private val neutral = Paint()
    private val accent = Paint()
    private var content: GlassTabContent? = null
    private var neutralIcons: IconLayer? = null
    private var accentIcons: IconLayer? = null
    private var previewIcons: IconLayer? = null

    fun update(value: GlassTabContent) {
        if (content == value) return
        if (content?.neutralColor != value.neutralColor) neutral.colorFilter = BlendModeColorFilter(value.neutralColor, BlendMode.SRC_IN)
        if (content?.accentColor != value.accentColor) accent.colorFilter = BlendModeColorFilter(value.accentColor, BlendMode.SRC_IN)
        content = value
        tint.reset()
        protected.reset()
        icons.reset()
        previewArea.reset()
        value.tintAreas.forEach { tint.addRect(it, Path.Direction.CW) }
        value.iconAreas.forEach { icons.addRect(it, Path.Direction.CW) }
        value.protectedAreas.forEach { protected.addRect(it, Path.Direction.CW) }
        tint.op(protected, Path.Op.DIFFERENCE)
        icons.op(protected, Path.Op.DIFFERENCE)
        tint.op(icons, Path.Op.DIFFERENCE)
        allInk.op(tint, icons, Path.Op.UNION)
        value.selectedPreviews.forEach { previewArea.addRect(it.bounds, Path.Direction.CW) }
        previewArea.op(icons, Path.Op.INTERSECT)
        neutralIcons?.update(value, value.neutralColor)
        accentIcons?.update(value, value.accentColor)
        previewIcons?.update(value, value.accentColor, preview = true)
    }

    /** Reuse native display lists; never recolor or replace the host's live Drawable. */
    fun captureIcons(width: Int, height: Int, margin: Int, drawNative: (Canvas) -> Unit) {
        val value = content ?: return
        if (value.iconAreas.isEmpty()) return
        val normal = neutralIcons ?: IconLayer().also { neutralIcons = it; it.update(value, value.neutralColor) }
        val selected = accentIcons ?: IconLayer().also { accentIcons = it; it.update(value, value.accentColor) }
        normal.record(width, height, margin, drawNative)
        selected.record(width, height, margin, drawNative)
        if (value.selectedPreviews.isNotEmpty()) {
            val previews = previewIcons ?: IconLayer().also {
                previewIcons = it; it.update(value, value.accentColor, preview = true)
            }
            previews.record(width, height, margin) { canvas ->
                value.selectedPreviews.forEach { preview ->
                    val save = canvas.save()
                    canvas.translate(preview.bounds.left, preview.bounds.top)
                    canvas.drawRenderNode(preview.node)
                    canvas.restoreToCount(save)
                }
            }
        }
    }

    fun drawNeutral(canvas: Canvas, drawNative: (Canvas) -> Unit) = drawTinted(canvas, neutral, neutralIcons, drawNative)

    fun drawAccent(canvas: Canvas, progress: Float, drawNative: (Canvas) -> Unit) {
        val tabs = content?.tabs.orEmpty()
        if (progress <= 0f || tabs.isEmpty()) {
            drawTinted(canvas, accent, accentIcons, drawNative)
            return
        }
        // Scale the hidden accent content for every tab, not the live/nearest host tab.
        val scale = 1f + .2f * progress.coerceIn(0f, 1f)
        for (tab in tabs) {
            val save = canvas.save()
            canvas.clipRect(tab)
            canvas.scale(scale, scale, tab.centerX(), tab.centerY())
            drawTinted(canvas, accent, accentIcons, drawNative)
            canvas.restoreToCount(save)
        }
    }

    private fun drawTinted(canvas: Canvas, paint: Paint, iconLayer: IconLayer?, drawNative: (Canvas) -> Unit) {
        // Disjoint clips avoid double-compositing antialiased glyph edges. SRC_IN is applied
        // to transparent native ink, before compositing it over the glass background.
        var save = canvas.save()
        canvas.clipOutPath(allInk)
        drawNative(canvas)
        canvas.restoreToCount(save)
        save = canvas.save()
        canvas.clipPath(tint)
        val layer = canvas.saveLayer(null, paint)
        drawNative(canvas)
        canvas.restoreToCount(layer)
        canvas.restoreToCount(save)
        if (!icons.isEmpty) {
            save = canvas.save()
            canvas.clipPath(icons)
            val replacement = previewIcons.takeIf { paint === accent && !previewArea.isEmpty }
            val original = canvas.save()
            if (replacement != null) canvas.clipOutPath(previewArea)
            if (iconLayer != null) iconLayer.draw(canvas) else drawNative(canvas)
            canvas.restoreToCount(original)
            if (replacement != null) {
                canvas.clipPath(previewArea)
                replacement.draw(canvas)
            }
            canvas.restoreToCount(save)
        }
    }

    fun release() {
        neutralIcons?.release()
        accentIcons?.release()
        previewIcons?.release()
        neutralIcons = null
        accentIcons = null
        previewIcons = null
    }

    private class IconLayer {
        private val shader = RuntimeShader(GLASS_TAB_INK_AGSL)
        private val node = RenderNode("QDHelper.Glass.TabIconInk")
        private var margin = 0

        fun update(content: GlassTabContent, color: Int, preview: Boolean = false) {
            shader.setColorUniform("inkColor", color)
            shader.setColorUniform("brandColor", content.accentColor)
            shader.setFloatUniform("night", if (content.night) 1f else 0f)
            val rect = if (preview) RectF(-1e6f, -1e6f, 1e6f, 1e6f) else content.selectedTab ?: RectF()
            shader.setFloatUniform("selectedBounds", rect.left, rect.top, rect.right, rect.bottom)
            // RenderEffect captures uniforms; recreate only when the palette/geometry changes.
            node.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"))
        }

        fun record(width: Int, height: Int, margin: Int, drawNative: (Canvas) -> Unit) {
            if (this.margin != margin) {
                this.margin = margin
                shader.setFloatUniform("origin", margin.toFloat(), margin.toFloat())
                node.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"))
            }
            val w = width + margin * 2
            val h = height + margin * 2
            node.setPosition(0, 0, w, h)
            val canvas = node.beginRecording(w, h)
            try { canvas.translate(margin.toFloat(), margin.toFloat()); drawNative(canvas) }
            finally { node.endRecording() }
        }

        fun draw(canvas: Canvas) {
            val save = canvas.save()
            canvas.translate(-margin.toFloat(), -margin.toFloat())
            canvas.drawRenderNode(node)
            canvas.restoreToCount(save)
        }

        fun release() { node.setRenderEffect(null); node.discardDisplayList() }
    }
}

/** Host icons contain opaque white detail (not alpha holes), notably the scroll-to-top arrow.
 * Only replace neutral/brand ink. Preserve other artwork colors, coverage and internal contrast.
 */
internal const val GLASS_TAB_INK_AGSL = """
uniform shader content;
layout(color) uniform half4 inkColor;
layout(color) uniform half4 brandColor;
uniform float2 origin;
uniform float4 selectedBounds;
uniform float night;
half4 main(float2 point) {
    half4 sample = content.eval(point);
    if (sample.a <= 0.0) return sample;
    half3 rgb = sample.rgb / sample.a;
    half hi = max(rgb.r, max(rgb.g, rgb.b));
    half lo = min(rgb.r, min(rgb.g, rgb.b));
    half chroma = hi - lo;
    half brandLo = min(brandColor.r, min(brandColor.g, brandColor.b));
    half brandChroma = max(brandColor.r, max(brandColor.g, brandColor.b)) - brandLo;
    half3 hue = (rgb - lo) / max(chroma, 0.001);
    half3 brandHue = (brandColor.rgb - brandLo) / max(brandChroma, 0.001);
    half brandInk = (1.0 - smoothstep(0.15, 0.4, length(hue - brandHue))) * smoothstep(0.04, 0.18, chroma);
    float2 local = point - origin;
    bool selected = local.x >= selectedBounds.x && local.x < selectedBounds.z &&
        local.y >= selectedBounds.y && local.y < selectedBounds.w;
    half gray = 1.0 - smoothstep(0.03, 0.12, chroma);
    half normalInk = selected ? 0.0 : gray * (night > 0.5 ? smoothstep(0.15, 0.75, hi) : 1.0 - smoothstep(0.25, 0.9, hi));
    half3 result = mix(rgb, inkColor.rgb, max(brandInk, normalInk));
    // White details must remain visible even in the neutral night row's near-white icon.
    if (selected && dot(inkColor.rgb, half3(0.2126, 0.7152, 0.0722)) > 0.65) {
        result = mix(result, half3(0.08), gray * smoothstep(0.6, 0.95, hi));
    }
    return half4(result * sample.a, sample.a);
}
"""
