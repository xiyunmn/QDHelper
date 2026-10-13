package com.xiyunmn.qdhelper.config

/** Per-location geometry. Zero width and a negative gap select the navigation-aware defaults. */
data class GlassBarDimensions(
    val heightDp: Int = 64,
    val widthPercent: Int = 0,
    val bottomGapDp: Int = -1,
) {
    fun normalized() = copy(
        heightDp = heightDp.coerceIn(MIN_HEIGHT, MAX_HEIGHT),
        widthPercent = if (widthPercent == 0) 0 else widthPercent.coerceIn(MIN_WIDTH, MAX_WIDTH),
        bottomGapDp = bottomGapDp.coerceIn(-1, MAX_GAP),
    )

    companion object {
        const val MIN_HEIGHT = 48
        const val MAX_HEIGHT = 120
        const val MIN_WIDTH = 60
        const val MAX_WIDTH = 100
        const val AUTO_WIDTH_TICK = MIN_WIDTH - 1
        const val MAX_GAP = 120

        // The shared slider stores integer strings. Its first width tick is Auto, like the reference.
        fun fromStored(height: String, width: String, gap: String): GlassBarDimensions {
            val widthTick = width.toIntOrNull()?.coerceIn(AUTO_WIDTH_TICK, MAX_WIDTH) ?: AUTO_WIDTH_TICK
            return GlassBarDimensions(
                height.toIntOrNull() ?: 64,
                if (widthTick == AUTO_WIDTH_TICK) 0 else widthTick,
                gap.toIntOrNull() ?: -1,
            ).normalized()
        }
    }
}
