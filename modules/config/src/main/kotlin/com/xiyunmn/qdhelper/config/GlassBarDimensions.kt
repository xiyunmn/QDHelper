package com.xiyunmn.qdhelper.config

/** Per-location geometry. Zero width and a negative gap select the navigation-aware defaults. */
data class GlassBarDimensions(
    val heightDp: Int = DEFAULT_HEIGHT,
    val widthPercent: Int = DEFAULT_WIDTH,
    val bottomGapDp: Int = DEFAULT_GAP,
) {
    fun normalized() = copy(
        heightDp = heightDp.coerceIn(MIN_HEIGHT, MAX_HEIGHT),
        widthPercent = if (widthPercent == 0) 0 else widthPercent.coerceIn(MIN_WIDTH, MAX_WIDTH),
        bottomGapDp = bottomGapDp.coerceIn(-1, MAX_GAP),
    )

    companion object {
        const val DEFAULT_HEIGHT = 58
        const val DEFAULT_WIDTH = 90
        const val DEFAULT_GAP = 20
        const val MIN_HEIGHT = 48
        const val MAX_HEIGHT = 68
        const val MIN_WIDTH = 80
        const val MAX_WIDTH = 100
        const val AUTO_WIDTH_TICK = MIN_WIDTH - 1
        const val MAX_GAP = 40

        // The shared slider stores integer strings; its first width tick selects Auto.
        fun fromStored(height: String, width: String, gap: String): GlassBarDimensions {
            val widthTick = width.toIntOrNull()?.coerceIn(AUTO_WIDTH_TICK, MAX_WIDTH) ?: DEFAULT_WIDTH
            return GlassBarDimensions(
                height.toIntOrNull() ?: DEFAULT_HEIGHT,
                if (widthTick == AUTO_WIDTH_TICK) 0 else widthTick,
                gap.toIntOrNull() ?: DEFAULT_GAP,
            ).normalized()
        }
    }
}
