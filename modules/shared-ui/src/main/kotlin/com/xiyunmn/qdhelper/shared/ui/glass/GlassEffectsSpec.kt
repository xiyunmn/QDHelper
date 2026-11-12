package com.xiyunmn.qdhelper.shared.ui.glass

data class GlassEffectsSpec(
    val blurRadiusDp: Float = 7f,
    val refractionScale: Float = 1f,
    val surfaceOpacity: Float = 0.5f,
    val highlightOpacity: Float = 0.75f,
    val shadowScale: Float = 1f,
    val dragStrength: Float = 1f,
    val capsuleScale: Float = 1.05f,
    val clickSpeed: Float = 0.6f,
    val tilt: Boolean = true,
    val press: Boolean = true,
) {
    val blur: Boolean get() = blurRadiusDp > 0f
    val refraction: Boolean get() = refractionScale > 0f
    val highlight: Boolean get() = highlightOpacity > 0f
    val shadow: Boolean get() = shadowScale > 0f
    val tracksLight: Boolean get() = highlight && tilt
    val refractionDp: Float get() = 32f * refractionScale
}
