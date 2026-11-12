package com.xiyunmn.qdhelper.shared.ui.glass

import kotlin.math.ceil
import kotlin.math.max

/** Shared capture and layer padding, in pixels; no frame content needs to be read back. */
object GlassRenderOutset {
    fun pixels(
        width: Int,
        height: Int,
        density: Float,
        effects: GlassEffectsSpec,
        maxSelectionWidth: Float = width.toFloat(),
    ): Int {
        val hasSelection = maxSelectionWidth > 0f
        val growth = if (effects.press && hasSelection && width > 0) {
            8f * density * max(1f, height.toFloat() / width)
        } else 0f
        val scaleX = effects.capsuleScale * if (effects.press) GlassBarMotion.MAX_SCALE_X else 1f
        val scaleY = effects.capsuleScale * if (effects.press) GlassBarMotion.MAX_SCALE_Y else 1f
        val deformation = if (hasSelection) max(
            maxSelectionWidth * (scaleX - 1f).coerceAtLeast(0f) / 2f +
                if (effects.press) height * scaleY * GlassBarMotion.MAX_SKEW / 2f else 0f,
            height * (scaleY - 1f).coerceAtLeast(0f) / 2f,
        ) else 0f
        val rubberOffset = if (effects.press && hasSelection) 4f * density else 0f
        fun blurSupport(radius: Float) = if (radius > 0f) ceil(3f * (0.57735f * radius + 0.5f)) else 0f
        val shadowKernel = if (effects.shadow) blurSupport(10f * density) else 0f
        val selectionShadow = if (effects.shadow && hasSelection) blurSupport(24f * density) + 4f * density else 0f
        val sampling = (effects.refractionDp + if (hasSelection) 9.8f * effects.refractionScale else 0f) * density +
            blurSupport(effects.blurRadiusDp * density)
        val motionExtent = deformation + if (hasSelection) GlassBarMotion.SELECTION_OVERSHOOT_DP * density else 0f
        return ceil(max(growth + shadowKernel, motionExtent + max(sampling, selectionShadow)) + rubberOffset).toInt()
    }
}
