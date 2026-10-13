package com.xiyunmn.qdhelper.shared.ui.glass

/** The six independent effects exposed by ForbidAd4TieBa's liquid-glass material. */
data class GlassEffectsSpec(
    val blur: Boolean = true,
    val refraction: Boolean = true,
    val highlight: Boolean = true,
    val shadow: Boolean = true,
    val tilt: Boolean = true,
    val press: Boolean = true,
) {
    val tracksLight: Boolean get() = highlight && tilt
    val blurRadiusDp: Float get() = if (blur) 4f else 0f
    val refractionDp: Float get() = if (refraction) 24f else 0f
}
