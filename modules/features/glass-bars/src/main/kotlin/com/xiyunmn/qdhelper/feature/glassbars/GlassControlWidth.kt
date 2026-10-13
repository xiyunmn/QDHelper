package com.xiyunmn.qdhelper.feature.glassbars

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.ceil
import kotlin.math.max

/** Only the small bar subtree, on layout. Includes newly inserted export/cloud controls. */
internal fun minimumControlWidth(view: View, flexibleControl: View? = null): Int {
    if (view.visibility == View.GONE) return 0
    val padding = view.paddingLeft + view.paddingRight
    val intrinsic = when (view) {
        is TextView -> ceil(view.paint.measureText(view.text?.toString().orEmpty())).toInt() +
            view.compoundPaddingLeft + view.compoundPaddingRight
        is ImageView -> (view.drawable?.intrinsicWidth ?: 0).coerceAtLeast(0) + padding
        is ViewGroup -> {
            var required = 0
            val horizontal = view is LinearLayout && view.orientation == LinearLayout.HORIZONTAL
            for (index in 0 until view.childCount) {
                val child = view.getChildAt(index)
                if (child.visibility == View.GONE) continue
                val params = child.layoutParams
                val margins = (params as? ViewGroup.MarginLayoutParams)?.let { it.leftMargin + it.rightMargin } ?: 0
                // QDBookDetailActivity allocates all remaining row width to goReadLayout. That
                // allocation is recomputed by the host when the bar narrows; it is not a minimum.
                val fixed = if (child === flexibleControl) 0 else params.width.coerceAtLeast(0)
                val childWidth = max(fixed, minimumControlWidth(child, flexibleControl)) + margins
                required = if (horizontal) required + childWidth else max(required, childWidth)
            }
            required + padding
        }
        else -> padding
    }
    val actionMinimum = if (view === flexibleControl) (100f * view.resources.displayMetrics.density).toInt() else 0
    return max(max(view.minimumWidth, actionMinimum), intrinsic)
}
