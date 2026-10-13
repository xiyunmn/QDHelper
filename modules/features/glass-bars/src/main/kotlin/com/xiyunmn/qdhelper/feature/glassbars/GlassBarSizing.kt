package com.xiyunmn.qdhelper.feature.glassbars

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.xiyunmn.qdhelper.config.GlassBarDimensions
import java.util.IdentityHashMap
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** Resize the native containers, keeping their children, listeners and dynamic action rows intact. */
internal class GlassBarSizing(private val binding: GlassBarBinding) : AutoCloseable {
    private class SizedView(val view: View) : AutoCloseable {
        val ownership = GlassViewOwnership()
        val height = ownership.own({ view.layoutParams.height }, {
            view.layoutParams = view.layoutParams.apply { height = it }
        })
        val gravity = (view as? LinearLayout)?.let { row -> ownership.own({ row.gravity }, { row.gravity = it }) }
        val margin = (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let { ownership.bottomMargin(view) }
        val frameGravity = (view.layoutParams as? FrameLayout.LayoutParams)?.let {
            ownership.own({ (view.layoutParams as FrameLayout.LayoutParams).gravity }, { value ->
                view.layoutParams = (view.layoutParams as FrameLayout.LayoutParams).apply { gravity = value }
            })
        }
        override fun close() = ownership.close()
    }

    private val views = IdentityHashMap<View, SizedView>()
    private val density = binding.bar.resources.displayMetrics.density
    private var nativeHeight = 0
    val extraHeight: Int get() = if (nativeHeight > 0) binding.bar.height - nativeHeight else 0

    fun update(dimensions: GlassBarDimensions) {
        if (nativeHeight == 0 && binding.bar.height > 0) nativeHeight = binding.bar.height
        val content = binding.tabs?.getChildAt(0) ?: binding.actionRow ?: binding.bar
        val target = max((dimensions.heightDp * density).roundToInt(), minimumHeight(content) + (8f * density).roundToInt())
        val next = linkedSetOf<View>()
        if (binding.kind != GlassBarKind.DIRECTORY) next += binding.bar
        binding.actionRow?.let(next::add)
        binding.tabs?.let { strip ->
            var node: View? = strip
            while (node != null && node !== binding.bar && node.isWithin(binding.bar)) {
                next += node
                node = node.parent as? View
            }
            strip.getChildAt(0)?.let(next::add)
        }
        views.keys.toList().filter { it !in next }.forEach { views.remove(it)?.close() }
        next.forEach { view ->
            val sized = views.getOrPut(view) { SizedView(view) }
            sized.height.set(target)
            sized.gravity?.update { (it and Gravity.VERTICAL_GRAVITY_MASK.inv()) or Gravity.CENTER_VERTICAL }
            if (binding.tabs != null && view !== binding.bar) {
                sized.margin?.set(0)
                sized.frameGravity?.update { (it.coerceAtLeast(0) and Gravity.VERTICAL_GRAVITY_MASK.inv()) or Gravity.CENTER_VERTICAL }
            }
        }
    }

    // Container heights may already be our allocation. Only intrinsic content and fixed leaves
    // establish the minimum, so dragging the height slider back down cannot retain its old maximum.
    private fun minimumHeight(view: View): Int {
        if (view.visibility == View.GONE) return 0
        val padding = view.paddingTop + view.paddingBottom
        val intrinsic = when (view) {
            is TextView -> ceil(view.paint.fontSpacing * max(1, view.lineCount)).toInt() +
                view.compoundPaddingTop + view.compoundPaddingBottom
            is ViewGroup -> {
                var required = 0
                val vertical = view is LinearLayout && view.orientation == LinearLayout.VERTICAL
                for (index in 0 until view.childCount) {
                    val child = view.getChildAt(index)
                    if (child.visibility == View.GONE) continue
                    val params = child.layoutParams
                    val margins = (params as? ViewGroup.MarginLayoutParams)?.let { it.topMargin + it.bottomMargin } ?: 0
                    val fixed = if (child is ViewGroup) 0 else params.height.coerceAtLeast(0)
                    val childHeight = max(fixed, minimumHeight(child)) + margins
                    required = if (vertical) required + childHeight else max(required, childHeight)
                }
                required + padding
            }
            else -> max(view.minimumHeight, view.layoutParams.height.coerceAtLeast(0)) + padding
        }
        // EXACTLY-sized native controls can be smaller than their theme's suggested minimum.
        // Preserve their real layout rather than adding the unused suggestion to the bar height.
        val minimum = if (view !is ViewGroup && view.layoutParams.height > 0) view.layoutParams.height else view.minimumHeight
        return max(minimum, intrinsic)
    }

    override fun close() {
        views.values.forEach { it.close() }
        views.clear()
    }
}
