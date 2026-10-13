package com.xiyunmn.qdhelper.feature.glassbars

import android.view.View
import android.view.ViewGroup
import android.widget.RelativeLayout
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks

internal class GlassViewOwnership : AutoCloseable {
    private val values = mutableListOf<AutoCloseable>()

    fun <T> own(read: () -> T, write: (T) -> Unit): OwnedProperty<T> = OwnedProperty(read, write).also(values::add)

    fun background(view: View) = own({ view.background }, { view.background = it })
    fun alpha(view: View) = own({ view.alpha }, { view.alpha = it })
    fun bottomMargin(view: View): OwnedProperty<Int> = own(
        { (view.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin },
        { value ->
            val params = view.layoutParams as ViewGroup.MarginLayoutParams
            params.bottomMargin = value
            view.layoutParams = params
        },
    )

    fun horizontalMargins(view: View): Pair<OwnedProperty<Int>, OwnedProperty<Int>> {
        val relative = (view.layoutParams as ViewGroup.MarginLayoutParams).isMarginRelative
        fun side(start: Boolean) = own(
            {
                val params = view.layoutParams as ViewGroup.MarginLayoutParams
                when {
                    relative && start -> params.marginStart
                    relative -> params.marginEnd
                    start -> params.leftMargin
                    else -> params.rightMargin
                }
            },
            { value ->
                val params = view.layoutParams as ViewGroup.MarginLayoutParams
                when {
                    relative && start -> params.marginStart = value
                    relative -> params.marginEnd = value
                    start -> params.leftMargin = value
                    else -> params.rightMargin = value
                }
                view.layoutParams = params
            },
        )
        return side(true) to side(false)
    }

    fun bottomPadding(view: View) = own(
        { view.paddingBottom },
        { value ->
            if (view.isPaddingRelative) view.setPaddingRelative(view.paddingStart, view.paddingTop, view.paddingEnd, value)
            else view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, value)
        },
    )

    fun aboveRule(view: View) = own(
        { (view.layoutParams as RelativeLayout.LayoutParams).getRule(RelativeLayout.ABOVE) },
        { value ->
            val params = view.layoutParams as RelativeLayout.LayoutParams
            if (value == 0) params.removeRule(RelativeLayout.ABOVE) else params.addRule(RelativeLayout.ABOVE, value)
            view.layoutParams = params
        },
    )

    override fun close() {
        values.asReversed().forEach {
            runCatching(it::close).onFailure { error -> FeatureHooks.w("Glass property restore failed: ${error.javaClass.simpleName}") }
        }
        values.clear()
    }
}
