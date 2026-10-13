package com.xiyunmn.qdhelper.feature.glassbars

import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import java.util.IdentityHashMap

/**
 * Discover once, then watch only the small set of non-scrolling containers for structural changes.
 * Recycler children are deliberately excluded. No whole-tree searches during drawing/scroll frames.
 */
internal class GlassContentInsets(
    private val binding: GlassBarBinding,
    private val flutter: FlutterGlassBridge,
    private val incompatible: (String) -> Unit,
) : AutoCloseable {
    private class NativeTarget(val view: View) : AutoCloseable {
        val ownership = GlassViewOwnership()
        val padding = ownership.bottomPadding(view)
        val clipping = (view as? ViewGroup)?.let { group -> ownership.own({ group.clipToPadding }, { group.clipToPadding = it }) }
        override fun close() = ownership.close()
    }

    private class Branch(val view: ViewGroup) {
        val children = Array(view.childCount) { view.getChildAt(it) }
        private val visibility = view.visibility
        private val childVisibility = IntArray(children.size) { children[it].visibility }
        fun changed(): Boolean = view.visibility != visibility || view.childCount != children.size || children.indices.any {
            view.getChildAt(it) !== children[it] || children[it].visibility != childVisibility[it]
        }
    }

    private class Discovery {
        val queue = ArrayDeque<ViewGroup>()
        val native = mutableListOf<View>()
        val flutter = mutableListOf<View>()
        val branches = mutableListOf<Branch>()
        val floating = mutableListOf<View>()
    }

    private val native = IdentityHashMap<View, NativeTarget>()
    private val floating = IdentityHashMap<View, OwnedProperty<Int>>()
    private val publishId = binding.source.resources.getIdentifier("add", "id", binding.source.context.packageName)
    private val publishBackgroundId = binding.source.resources.getIdentifier("addBG", "id", binding.source.context.packageName)
    private val flutterViews = mutableListOf<View>()
    private val branches = mutableListOf<Branch>()
    private val location = IntArray(2)
    private var discovered = false
    private var discovery: Discovery? = null
    private var closed = false
    private var barTop = 0
    private var viewportBottom = 0
    private var visible = false
    private var reportedNative = -1
    private var reportedFlutter = -1
    private val continueDiscovery = Runnable { discoverChunk() }

    fun update(barTop: Int, viewportBottom: Int, visible: Boolean) {
        if (closed) return
        this.barTop = barTop
        this.viewportBottom = viewportBottom
        this.visible = visible
        if (discovery == null && (!discovered || branches.any { it.changed() })) discover()
        applyInsets()
    }

    private fun applyInsets() {
        floating.forEach { (view, margin) ->
            val parent = view.parent as? View ?: return@forEach
            parent.getLocationOnScreen(location)
            val extra = GlassGeometry.occlusion(location[1] + parent.height, viewportBottom, barTop, visible)
            margin.update { it + extra }
        }
        val iterator = native.entries.iterator()
        while (iterator.hasNext()) {
            val (view, target) = iterator.next()
            if (!view.isWithin(binding.source)) {
                target.close()
                iterator.remove()
                continue
            }
            view.getLocationOnScreen(location)
            val extra = GlassGeometry.occlusion(location[1] + view.height, viewportBottom, barTop, visible)
            target.padding.update { it + extra }
            target.clipping?.set(false)
        }
        flutterViews.forEach { view ->
            view.getLocationOnScreen(location)
            flutter.update(view, GlassGeometry.occlusion(location[1] + view.height, viewportBottom, barTop, visible))
        }
    }

    fun engineAttached(view: View) {
        if (view.isWithin(binding.source)) discovered = false
    }

    private fun discover() {
        val next = Discovery()
        discovery = next
        val explicit = binding.nativeScroll
        if (explicit != null) {
            next.native += explicit
        } else {
            (binding.source as? ViewGroup)?.let(next.queue::add)
        }
        discoverChunk()
    }

    private fun discoverChunk() {
        val next = discovery ?: return
        if (closed) return
        // A large cached page is not an incompatible host. Continue discovery in bounded UI tasks
        // instead of reverting the whole bar when a page (notably Find) has >512 ordinary views.
        var visited = 0
        while (next.queue.isNotEmpty() && visited++ < 96) {
            val view = next.queue.removeFirst()
            if (!view.isWithin(binding.source)) continue
            if (view.visibility == View.GONE) {
                next.branches += Branch(view)
                continue
            }
            if (publishId != 0 && publishBackgroundId != 0 && view.id == publishId &&
                view.findViewById<View>(publishBackgroundId) != null && view.layoutParams is ViewGroup.MarginLayoutParams) {
                next.floating += view
            }
            if (view.isHostType(HostSymbols.Classes.FLUTTER_VIEW)) {
                next.flutter += view
                if (!flutter.available || !usesTexture(view)) incompatible("main.flutter-texture-or-metrics")
                continue
            }
            if (isVerticalScroll(view)) {
                next.native += view
                continue
            }
            // Recycler cells and horizontal decorations cannot contain the page's vertical list.
            if (view.isHostType(HostSymbols.Classes.ANDROIDX_RECYCLER_VIEW)) {
                if (FeatureHooks.invokeNoArgOrNull(view, "getLayoutManager") == null) next.branches += Branch(view)
                continue
            }
            if (view is HorizontalScrollView) continue
            next.branches += Branch(view)
            for (index in 0 until view.childCount) {
                (view.getChildAt(index) as? ViewGroup)?.let(next.queue::add)
            }
        }
        if (next.queue.isNotEmpty()) {
            binding.source.post(continueDiscovery)
            return
        }
        val foundNative = next.native
        val foundFlutter = next.flutter
        native.keys.toList().filter { it !in foundNative }.forEach { native.remove(it)?.close() }
        foundNative.forEach { view -> if (view !in native) native[view] = NativeTarget(view) }
        floating.keys.toList().filter { it !in next.floating }.forEach { floating.remove(it)?.close() }
        next.floating.forEach { view ->
            if (view !in floating) floating[view] = OwnedProperty(
                { (view.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin },
                { margin -> view.layoutParams = (view.layoutParams as ViewGroup.MarginLayoutParams).apply { bottomMargin = margin } },
            )
        }
        flutterViews.filter { it !in foundFlutter }.forEach(flutter::remove)
        flutterViews.clear()
        flutterViews += foundFlutter
        branches.clear()
        branches += next.branches
        discovery = null
        discovered = true
        applyInsets()
        if (reportedNative != native.size || reportedFlutter != flutterViews.size) {
            reportedNative = native.size
            reportedFlutter = flutterViews.size
            FeatureHooks.i("Glass content: kind=${binding.kind}, native=${native.size}, flutter=${flutterViews.size}, containers=${branches.size}")
        }
    }

    private fun usesTexture(view: View): Boolean {
        val group = view as? ViewGroup ?: return false
        var texture = false
        for (index in 0 until group.childCount) {
            when (group.getChildAt(index)) {
                is SurfaceView -> return false
                is TextureView -> texture = true
            }
        }
        return texture
    }

    private fun isVerticalScroll(view: View): Boolean {
        if (view is ScrollView || view is AbsListView ||
            view.isHostType(HostSymbols.Classes.ANDROIDX_NESTED_SCROLL_VIEW) ||
            view.isHostType(HostSymbols.Classes.QD_NESTED_SCROLL_VIEW)
        ) return true
        if (!view.isHostType(HostSymbols.Classes.ANDROIDX_RECYCLER_VIEW)) return false
        val manager = FeatureHooks.invokeNoArgOrNull(view, "getLayoutManager") ?: return false
        return FeatureHooks.invokeNoArgOrNull(manager, "canScrollVertically") == true
    }

    override fun close() {
        closed = true
        binding.source.removeCallbacks(continueDiscovery)
        discovery = null
        native.values.forEach(NativeTarget::close)
        native.clear()
        floating.values.forEach { it.close() }
        floating.clear()
        flutterViews.forEach(flutter::remove)
        flutterViews.clear()
        branches.clear()
    }
}
