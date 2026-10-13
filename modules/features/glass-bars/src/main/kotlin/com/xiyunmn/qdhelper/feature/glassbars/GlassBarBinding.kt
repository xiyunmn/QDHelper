package com.xiyunmn.qdhelper.feature.glassbars

import android.view.View
import android.view.ViewGroup
import android.widget.RelativeLayout
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.UiFeatureSupport
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols

internal data class GlassBarBinding(
    val kind: GlassBarKind,
    val parent: ViewGroup,
    val bar: ViewGroup,
    val source: View,
    val nativeScroll: View? = null,
    val clearBackgrounds: List<View> = listOf(bar),
    val decorations: List<View> = emptyList(),
    val mask: View? = null,
    val fastScroller: View? = null,
    val liftedAccessories: List<View> = emptyList(),
    val clipBar: Boolean = false,
    val flexibleControl: View? = null,
    val actionRow: ViewGroup? = null,
    val tabs: ViewGroup? = null,
)

internal sealed interface GlassBindingResult {
    data class Bound(val binding: GlassBarBinding) : GlassBindingResult
    data class Unavailable(val reason: String) : GlassBindingResult
}

internal interface GlassBarAdapter {
    val kind: GlassBarKind
    fun bind(root: View): GlassBindingResult
}

internal object MainGlassBarAdapter : GlassBarAdapter {
    override val kind = GlassBarKind.MAIN
    override fun bind(root: View): GlassBindingResult {
        val bar = root.named("bottom_tab_container") as? ViewGroup ?: return missing("main.bar")
        val parent = bar.parent as? RelativeLayout ?: return missing("main.parent")
        val pager = parent.named("pager") ?: return missing("main.pager")
        if (pager.parent !== parent || pager.layoutParams !is RelativeLayout.LayoutParams) return missing("main.pager-layout")
        val above = (pager.layoutParams as RelativeLayout.LayoutParams).getRule(RelativeLayout.ABOVE)
        if (above != 0 && above != bar.id) return missing("main.pager-anchor")
        val tabs = bar.named("tabs") as? ViewGroup ?: return missing("main.tabs")
        val background = bar.named("tab_bg") ?: return missing("main.background")
        val divider = bar.named("tab_divider") ?: return missing("main.divider")
        return GlassBindingResult.Bound(
            GlassBarBinding(kind, parent, bar, pager, decorations = listOf(background, divider), mask = parent.named("bottomMaskHolder"), tabs = tabs),
        )
    }
}

internal object DetailGlassBarAdapter : GlassBarAdapter {
    override val kind = GlassBarKind.DETAIL
    override fun bind(root: View): GlassBindingResult {
        val bar = root.named("showbook_bottom_btn_layout") as? ViewGroup ?: return missing("detail.bar")
        val parent = bar.parent as? ViewGroup ?: return missing("detail.parent")
        val scroll = parent.named("recycler_view_target") ?: return missing("detail.content")
        if (scroll.parent !== parent || !scroll.isHostType(HostSymbols.Classes.QD_NESTED_SCROLL_VIEW)) return missing("detail.scroll")
        if (bar.layoutParams !is ViewGroup.MarginLayoutParams || scroll.layoutParams !is ViewGroup.MarginLayoutParams) {
            return missing("detail.margins")
        }
        return GlassBindingResult.Bound(
            GlassBarBinding(
                kind, parent, bar, scroll, nativeScroll = scroll,
                clearBackgrounds = listOfNotNull(bar, bar.named("layoutBookAction")),
                decorations = (0 until parent.childCount).map(parent::getChildAt).filter {
                    // The book-detail XML has one anonymous hairline immediately before the bar.
                    it.javaClass == View::class.java && it.id == View.NO_ID &&
                        it.layoutParams.height in 1..2 &&
                        (it.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin == bar.layoutParams.height
                },
                liftedAccessories = listOfNotNull(parent.named("layJoinFansCamp"), parent.named("tagRedPackageGift")),
                flexibleControl = bar.named("goReadLayout"),
                actionRow = bar.named("layoutBookAction") as? ViewGroup,
            ),
        )
    }
}

internal object DirectoryGlassBarAdapter : GlassBarAdapter {
    override val kind = GlassBarKind.DIRECTORY
    override fun bind(root: View): GlassBindingResult {
        val bar = root.named("bottomContainer") as? ViewGroup ?: return missing("directory.bar")
        val parent = bar.parent as? RelativeLayout ?: return missing("directory.parent")
        val actions = bar.named("buyChapterLayout") as? ViewGroup ?: return missing("directory.actions")
        val source = parent.named("listDirectory") ?: return missing("directory.list")
        if (source.parent !== parent || source.layoutParams !is RelativeLayout.LayoutParams) return missing("directory.list-layout")
        val above = (source.layoutParams as RelativeLayout.LayoutParams).getRule(RelativeLayout.ABOVE)
        if (above != 0 && above != bar.id) return missing("directory.list-anchor")
        val recycler = FeatureHooks.invokeNoArgOrNull(source, "getQDRecycleView") as? View ?: return missing("directory.recycler")
        if (!recycler.isWithin(source)) return missing("directory.recycler-parent")
        return GlassBindingResult.Bound(
            GlassBarBinding(
                kind, parent, bar, source, nativeScroll = recycler,
                clearBackgrounds = listOfNotNull(bar, actions, bar.named("discountLayout")),
                fastScroller = parent.named("fastScrollBar"), clipBar = true,
                actionRow = actions,
            ),
        )
    }
}

private fun missing(reason: String) = GlassBindingResult.Unavailable(reason)

internal fun View.named(id: String): View? = UiFeatureSupport.findChildViewByIdName(this, id)

internal fun View.isWithin(ancestor: View): Boolean {
    var view: View? = this
    while (view != null) {
        if (view === ancestor) return true
        view = view.parent as? View
    }
    return false
}

internal fun View.isHostType(name: String): Boolean {
    var type: Class<*>? = javaClass
    while (type != null) {
        if (type.name == name) return true
        type = type.superclass
    }
    return false
}
