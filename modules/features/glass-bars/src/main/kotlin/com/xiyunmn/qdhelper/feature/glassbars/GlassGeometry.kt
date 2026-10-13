package com.xiyunmn.qdhelper.feature.glassbars

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal object GlassGeometry {
    // DecorView can already be shortened by adjustResize; window bounds retain the original bottom.
    fun consumedBottom(windowBottom: Int, rootBottom: Int, viewportBottom: Int): Int =
        (max(windowBottom, rootBottom) - viewportBottom).coerceAtLeast(0)

    fun viewportBottom(parentBottom: Int, rootBottom: Int): Int = min(parentBottom, rootBottom)

    fun bottomMargin(original: Int, navigationInset: Int, alreadyConsumed: Int, gap: Int, clippedBottom: Int = 0): Int =
        max(original, (navigationInset - alreadyConsumed).coerceAtLeast(0)) + gap + clippedBottom.coerceAtLeast(0)

    /** Preserve the host's minimum content width, sacrificing the optional side gap first. */
    fun sideMargin(parentWidth: Int, minimumBarWidth: Int, requested: Int): Int =
        min(requested, ((parentWidth - minimumBarWidth).coerceAtLeast(0) / 2))

    fun widthSideMargin(parentWidth: Int, minimumBarWidth: Int, widthPercent: Int, automaticWidth: Int): Int {
        val available = parentWidth.coerceAtLeast(0)
        val requested = if (widthPercent == 0) automaticWidth else (available * widthPercent / 100f).roundToInt()
        val width = requested.coerceIn(minimumBarWidth.coerceIn(0, available), available)
        return (available - width) / 2
    }

    fun bottomGapDp(requested: Int, navigationInset: Int): Int =
        if (requested >= 0) requested else if (navigationInset > 0) 8 else 28

    fun occlusion(viewBottom: Int, viewportBottom: Int, barTop: Int, visible: Boolean): Int =
        // Some host pages measure their content to the window height *below* a toolbar. The
        // clipped part is also inaccessible scroll space and must be included in the trailing pad.
        if (visible) (viewBottom - min(viewportBottom, barTop)).coerceAtLeast(0) else 0

    fun flutterPadding(original: Int, occlusion: Int): Int = max(original, occlusion.coerceAtLeast(0))
}
