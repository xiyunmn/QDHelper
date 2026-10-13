package com.xiyunmn.qdhelper.feature.glassbars

import android.graphics.RectF
import android.graphics.RenderNode
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.shared.ui.UiColors
import com.xiyunmn.qdhelper.shared.ui.glass.GlassTabContent
import com.xiyunmn.qdhelper.shared.ui.glass.GlassTabIconPreview

/** Host identities stay here; the shared GPU renderer only receives geometry and colors. */
@android.annotation.TargetApi(33)
internal class GlassTabHighlightContent(private val bar: View, private val strip: ViewGroup) : AutoCloseable {
    private data class Tab(val view: View, val labels: List<View>, val icons: List<View>, val badges: List<View>)
    private val currentItem = FeatureHooks.publicMethodOrNull(strip.javaClass, "getCurrentItem", 0)
    private var tabs = emptyList<Tab>()
    private val origin = IntArray(2)
    private val location = IntArray(2)
    private data class Preview(val drawable: Drawable, val layout: List<Any>, val node: RenderNode)
    private val previews = mutableMapOf<ImageView, Preview>()
    private var previewUnavailable = false

    fun read(): GlassTabContent? {
        val row = strip.getChildAt(0) as? ViewGroup ?: return null
        if (tabs.size != row.childCount || tabs.indices.any { tabs[it].view !== row.getChildAt(it) }) {
            close()
            tabs = (0 until row.childCount).map { index ->
                val view = row.getChildAt(index)
                Tab(view,
                    listOfNotNull(view.named("view_tab_title_title")),
                    listOfNotNull(view.named("view_tab_title_icon")),
                    listOfNotNull(view.named("dotImg"), view.named("operationRedDot"), view.named("tagView"),
                        view.named("attentionAdIcon"), view.named("attentionAdPag"), view.named("view_tab_pag_view")))
            }
        }
        bar.getLocationOnScreen(origin)
        val visible = tabs.filter { it.view.isShown && it.view.width > 0 }
        val labels = visible.flatMap { it.labels }.filter(View::isShown).map(::bounds)
        val icons = visible.flatMap { it.icons }.filter(View::isShown).map(::bounds)
        if (labels.isEmpty() && icons.isEmpty()) return null // Unknown layout: preserve native rendering.
        val selected = (FeatureHooks.invokeOrNull(currentItem, strip) as? Int)?.let { tabs.getOrNull(it) }
        return GlassTabContent(visible.map { bounds(it.view) }, labels,
            visible.flatMap { it.badges }.filter(View::isShown).map(::bounds), UiColors.Text, UiColors.Brand,
            icons, selected?.view?.takeIf(View::isShown)?.let(::bounds), UiColors.IsNight,
            visible.filter { it !== selected }.flatMap { it.icons }.filterIsInstance<ImageView>()
                .filter { it.isShown && it.width > 0 && it.height > 0 }.mapNotNull(::selectedPreview))
    }

    private fun selectedPreview(icon: ImageView): GlassTabIconPreview? {
        if (previewUnavailable) return null
        return try { recordSelectedPreview(icon) } catch (error: Exception) {
            previewUnavailable = true
            close()
            FeatureHooks.w("Glass selected preview unavailable: ${error.javaClass.simpleName}")
            null
        }
    }

    private fun recordSelectedPreview(icon: ImageView): GlassTabIconPreview? {
        val drawable = GlassNativeTabAppearance.selectedArtwork(icon) ?: return null
        val layout = listOf(icon.width, icon.height, icon.paddingLeft, icon.paddingTop, icon.paddingRight,
            icon.paddingBottom, icon.scaleType, icon.layoutDirection,
            if (icon.scaleType == ImageView.ScaleType.MATRIX) icon.imageMatrix.toShortString() else "")
        var preview = previews[icon]
        if (preview?.drawable !== drawable || preview.layout != layout || !preview.node.hasDisplayList()) {
            preview?.node?.discardDisplayList()
            // Use framework ImageView sizing/scaleType; do not guess native icon placement.
            val probe = ImageView(icon.context).apply {
                scaleType = icon.scaleType
                layoutDirection = icon.layoutDirection
                setPadding(icon.paddingLeft, icon.paddingTop, icon.paddingRight, icon.paddingBottom)
                setImageDrawable(drawable)
                imageMatrix = icon.imageMatrix
                layout(0, 0, icon.width, icon.height)
            }
            val node = RenderNode("QDHelper.Glass.SelectedNativeIcon")
            node.setPosition(0, 0, icon.width, icon.height)
            val canvas = node.beginRecording(icon.width, icon.height)
            try { probe.draw(canvas) } finally { node.endRecording(); probe.setImageDrawable(null) }
            preview = Preview(drawable, layout, node)
            previews[icon] = preview
        }
        return GlassTabIconPreview(bounds(icon), preview.node)
    }

    override fun close() {
        previews.values.forEach { it.node.discardDisplayList() }
        previews.clear()
    }

    private fun bounds(view: View): RectF {
        view.getLocationOnScreen(location)
        val x = (location[0] - origin[0]).toFloat()
        val y = (location[1] - origin[1]).toFloat()
        return RectF(x, y, x + view.width, y + view.height)
    }
}
