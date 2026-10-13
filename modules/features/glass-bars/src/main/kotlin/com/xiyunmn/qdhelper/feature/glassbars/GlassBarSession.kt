package com.xiyunmn.qdhelper.feature.glassbars

import android.content.res.Configuration
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import com.xiyunmn.qdhelper.config.GlassBarDimensions
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.shared.ui.UiColors
import com.xiyunmn.qdhelper.feature.glassbars.compose.ComposeGlassBar
import com.xiyunmn.qdhelper.shared.compose.ModuleComposeContext
import com.xiyunmn.qdhelper.shared.ui.glass.GlassEffectsSpec
import java.util.AbstractMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Owns a single reversible binding. Host children and listeners never move. Main thread only. */
@android.annotation.TargetApi(33)
internal class GlassBarSession(
    val binding: GlassBarBinding,
    flutter: FlutterGlassBridge,
    private val incompatible: (String) -> Unit,
) : AutoCloseable {
    init {
        require(!binding.bar.isWithin(binding.source) && !binding.source.isWithin(binding.bar)) { "Cyclic glass source" }
    }

    private val parent = binding.parent
    private val bar = binding.bar
    private val density = bar.resources.displayMetrics.density
    private val ownership = GlassViewOwnership()
    private val backgrounds = binding.clearBackgrounds.map(ownership::background)
    private val decorations = binding.decorations.map(ownership::alpha)
    private val sides = ownership.horizontalMargins(bar)
    private val bottom = ownership.bottomMargin(bar)
    private val directoryTranslation = if (binding.kind == GlassBarKind.DIRECTORY)
        ownership.own({ bar.translationY }, { bar.translationY = it }) else null
    private val sizing = GlassBarSizing(binding)
    private val reserve = if (binding.kind == GlassBarKind.DETAIL) ownership.bottomMargin(binding.source) else ownership.aboveRule(binding.source)
    private val maskSides = binding.mask?.let(ownership::horizontalMargins)
    private val maskBottom = binding.mask?.let(ownership::bottomMargin)
    private val maskHeight = binding.mask?.let { mask ->
        ownership.own({ mask.layoutParams.height }, { height -> mask.layoutParams = mask.layoutParams.apply { this.height = height } })
    }
    private val maskTranslation = binding.mask?.let { mask ->
        ownership.own({ mask.translationY }, { mask.translationY = it })
    }
    private val accessoryBottoms = binding.liftedAccessories.map(ownership::bottomMargin)
    private val fastBottom = binding.fastScroller?.let(ownership::bottomMargin)
    private val content = GlassContentInsets(binding, flutter, incompatible)
    private val glass = ComposeGlassBar(ModuleComposeContext.create(bar.context), binding.source, binding.tabs) { error ->
        FeatureHooks.w("Compose glass unavailable: kind=${binding.kind}, error=${error.javaClass.simpleName}")
        incompatible("${binding.kind.name.lowercase()}.compose-${error.javaClass.simpleName}")
    }
    private val selection = binding.tabs?.let { GlassTabSelection(bar, it, glass.motion) }
    private val tabHighlight = binding.tabs?.let { GlassTabHighlightContent(bar, it) }
    private val selectionOverlay = glass.foreground
    private val dragTranslation = binding.tabs?.let { ownership.own({ bar.translationX }, { bar.translationX = it }) }
    private val parentLocation = IntArray(2)
    private val rootLocation = IntArray(2)
    private val barLocation = IntArray(2)
    private val lastRootBounds = Rect()
    private val reportedBounds = Rect()
    private var reportedViewport = -1
    private var windowBottom = Int.MIN_VALUE
    private var effects = GlassEffectsSpec()
    private var dimensions = GlassBarDimensions()
    private var night = false
    private var themeBackground: Any? = null
    private var themeColor: Int? = null
    private var themeMode = -1
    private var observer: ViewTreeObserver? = null
    private var applying = false
    var closed = false
        private set

    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { refreshLayout() }
    private val drawListener = ViewTreeObserver.OnPreDrawListener {
        if (!closed) {
            refreshTheme(force = false)
            if (!bar.isShown) selection?.cancelTouch()
            mirrorBar()
            glass.setTabContent(tabHighlight?.read())
            glass.prepare()
        }
        true
    }
    private val detachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) = close()
    }

    fun attach(spec: GlassEffectsSpec, active: Boolean, dimensions: GlassBarDimensions = GlassBarDimensions()) {
        // A boot-classpath lease also lets a new module classloader release an old drawing layer.
        releasePreviousLease()
        effects = spec
        this.dimensions = dimensions.normalized()
        glass.motion.onInteractionOffset = { offset -> dragTranslation?.update { it + offset } }
        glass.background.tag = AbstractMap.SimpleImmutableEntry(LEASE_TAG, this as AutoCloseable)
        observer = parent.viewTreeObserver.also {
            it.addOnGlobalLayoutListener(layoutListener)
            it.addOnPreDrawListener(drawListener)
        }
        parent.addView(glass.background, parent.indexOfChild(bar), ViewGroup.LayoutParams(0, 0))
        selectionOverlay?.let { parent.addView(it, parent.indexOfChild(bar) + 1, ViewGroup.LayoutParams(0, 0)) }
        bar.addOnAttachStateChangeListener(detachListener)
        val clippedViews = listOfNotNull(binding.mask, bar.takeIf { binding.clipBar })
        clippedViews.forEach { mask ->
            val outline = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, min(view.width, view.height) / 2f)
                }
            }
            ownership.own({ mask.outlineProvider }, { mask.outlineProvider = it }).set(outline)
            ownership.own({ mask.clipToOutline }, { mask.clipToOutline = it }).set(true)
        }
        refreshTheme(force = true)
        setActive(active)
        refreshLayout()
    }

    fun update(spec: GlassEffectsSpec, dimensions: GlassBarDimensions = this.dimensions) {
        if (closed) return
        effects = spec
        this.dimensions = dimensions.normalized()
        refreshTheme(force = true)
        refreshLayout()
    }

    fun setActive(active: Boolean) {
        if (!active) selection?.cancelTouch()
        glass.setActive(active)
        if (active) refreshTheme(force = true)
    }

    fun observeTouch(action: Int, x: Float, y: Float, eventTime: Long) {
        if (closed || !bar.isShown) return
        selection?.observeTouch(action, x, y, eventTime)
        selection?.update(glass.motion)
        bar.getLocationOnScreen(barLocation)
        glass.motion.observeTouch(action, x - barLocation[0], y - barLocation[1])
    }

    fun engineAttached(view: View) {
        content.engineAttached(view)
        refreshLayout()
    }

    private fun refreshLayout() {
        if (closed || applying || bar.parent !== parent) return
        applying = true
        try {
            refreshTheme(force = false)
            sizing.update(dimensions)
            val root = parent.rootView
            root.getLocationOnScreen(rootLocation)
            parent.getLocationOnScreen(parentLocation)
            val parentBottom = parentLocation[1] + parent.height - parent.paddingBottom
            val viewportBottom = GlassGeometry.viewportBottom(parentBottom, rootLocation[1] + root.height)
            val clippedBottom = parentBottom - viewportBottom
            val consumed = GlassGeometry.consumedBottom(windowBottom(root), rootLocation[1] + root.height, viewportBottom)
            val nav = parent.rootWindowInsets?.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout())?.bottom ?: 0
            val gap = (GlassGeometry.bottomGapDp(dimensions.bottomGapDp, nav) * density).roundToInt()
            val availableWidth = parent.width - parent.paddingLeft - parent.paddingRight
            val minimumWidth = if (binding.kind == GlassBarKind.MAIN) mainControlWidth() else minimumControlWidth(bar, binding.flexibleControl)
            val automaticWidth = if (binding.tabs != null) minimumWidth else availableWidth - (32f * density).roundToInt()
            val side = GlassGeometry.widthSideMargin(availableWidth, minimumWidth, dimensions.widthPercent, automaticWidth)
            sides.first.update { max(it, side) }
            sides.second.update { max(it, side) }
            var lift = 0
            bottom.update { original ->
                GlassGeometry.bottomMargin(original, nav, consumed, gap, clippedBottom).also { lift = it - original }
            }
            reserve.set(0)
            backgrounds.forEach { it.set(null) }
            decorations.forEach { it.set(0f) }

            val actualBottom = (bar.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
            maskSides?.first?.update { max(it, side) }
            maskSides?.second?.update { max(it, side) }
            maskBottom?.set(actualBottom)
            accessoryBottoms.forEach { property -> property.update { it + lift + sizing.extraHeight } }
            bar.getLocationOnScreen(barLocation)
            if (bar.width > 0 && bar.height > 0 && (reportedViewport != viewportBottom ||
                reportedBounds.left != barLocation[0] || reportedBounds.top != barLocation[1] ||
                reportedBounds.width() != bar.width || reportedBounds.height() != bar.height)
            ) {
                reportedBounds.set(barLocation[0], barLocation[1], barLocation[0] + bar.width, barLocation[1] + bar.height)
                reportedViewport = viewportBottom
                FeatureHooks.i("Glass geometry: kind=${binding.kind}, bar=$reportedBounds, viewportBottom=$viewportBottom, nav=$nav, clipped=$clippedBottom, bottomMargin=$actualBottom")
            }
            val visible = bar.visibility == View.VISIBLE
            val occlusion = GlassGeometry.occlusion(parentBottom, viewportBottom, barLocation[1], visible)
            fastBottom?.update { max(it, occlusion) }
            content.update(barLocation[1], viewportBottom, visible)
            mirrorBar()
        } catch (error: Exception) {
            FeatureHooks.w("Glass layout unavailable: kind=${binding.kind}, error=${error.javaClass.simpleName}")
            incompatible("${binding.kind.name.lowercase()}.layout")
        } finally {
            applying = false
        }
    }

    private fun mirrorBar() {
        directoryTranslation?.update { original ->
            parent.getLocationOnScreen(parentLocation)
            parent.rootView.getLocationOnScreen(rootLocation)
            val visibleBottom = min(parentLocation[1] + parent.height - parent.paddingBottom, rootLocation[1] + parent.rootView.height)
            val margin = (bar.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
            // Some host layout passes leave RelativeLayout's cached bottom at the old anchor.
            // Correct only the missing lift, measured without our previous translation.
            val desiredBottom = parentLocation[1] + parent.height - parent.paddingBottom - margin
            val nav = parent.rootWindowInsets?.getInsets(WindowInsets.Type.navigationBars())?.bottom ?: 0
            val gap = (GlassGeometry.bottomGapDp(dimensions.bottomGapDp, nav) * density).roundToInt()
            val limit = min(visibleBottom - gap, desiredBottom)
            original + min(0f, limit - (parentLocation[1] + bar.bottom).toFloat())
        }
        val visibility = if (bar.visibility == View.VISIBLE) View.VISIBLE else View.INVISIBLE
        glass.background.visibility = visibility
        selectionOverlay?.visibility = visibility
        if (bar.width <= 0 || bar.height <= 0) return
        mirrorLayer(glass.background)
        selectionOverlay?.let(::mirrorLayer)
        maskHeight?.set(bar.height)
        maskTranslation?.update { it + bar.translationY }
        glass.configure(bar.width, bar.height, effects, night)
        selection?.update(glass.motion)
    }

    private fun mirrorLayer(layer: View) {
        val params = layer.layoutParams
        val width = bar.width + 2 * glass.halo
        val height = bar.height + 2 * glass.halo
        if (params.width != width || params.height != height) {
            params.width = width
            params.height = height
            layer.layoutParams = params
        }
        layer.translationX = bar.x - layer.left - glass.halo
        layer.translationY = bar.y - layer.top - glass.halo
        layer.elevation = bar.elevation
        layer.translationZ = bar.translationZ
        layer.alpha = bar.alpha
    }

    private fun mainControlWidth(): Int {
        val row = binding.tabs?.getChildAt(0) as? ViewGroup ?: return 0
        var count = 0
        var column = (48f * density).roundToInt()
        for (index in 0 until row.childCount) {
            val tab = row.getChildAt(index)
            if (tab.visibility == View.GONE) continue
            count++
            column = max(column, minimumControlWidth(tab) + (18f * density).roundToInt())
        }
        // The host allocates each tab from the previous strip width. Treating those allocated
        // widths as minima makes the bar expand and shrink on tab rebuilds/page transitions.
        return count * column + bar.paddingLeft + bar.paddingRight + (12f * density).roundToInt()
    }

    private fun refreshTheme(force: Boolean) {
        val mode = bar.resources.configuration.uiMode
        val background = parent.background
        val color = (background as? ColorDrawable)?.color
        if (!force && themeMode == mode && themeBackground === background && themeColor == color) return
        themeMode = mode
        themeBackground = background
        themeColor = color
        UiColors.refresh(bar.context)
        night = UiColors.hostThemeResolver?.isNightMode(bar.context)
            ?: (mode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
    }

    private fun windowBottom(root: View): Int {
        val right = rootLocation[0] + root.width
        val bottom = rootLocation[1] + root.height
        if (windowBottom == Int.MIN_VALUE || lastRootBounds.left != rootLocation[0] || lastRootBounds.top != rootLocation[1] ||
            lastRootBounds.right != right || lastRootBounds.bottom != bottom
        ) {
            lastRootBounds.set(rootLocation[0], rootLocation[1], right, bottom)
            // Query only when the window moves/resizes, never for each list layout or draw frame.
            windowBottom = runCatching {
                bar.context.getSystemService(WindowManager::class.java)?.currentWindowMetrics?.bounds?.bottom
            }.getOrNull() ?: bottom
        }
        return windowBottom
    }

    private fun releasePreviousLease() {
        for (index in parent.childCount - 1 downTo 0) {
            val tag = parent.getChildAt(index).tag as? Map.Entry<*, *> ?: continue
            if (tag.key == LEASE_TAG) (tag.value as? AutoCloseable)?.close()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        selection?.cancelTouch()
        observer?.takeIf { it.isAlive }?.let {
            it.removeOnGlobalLayoutListener(layoutListener)
            it.removeOnPreDrawListener(drawListener)
        }
        observer = null
        bar.removeOnAttachStateChangeListener(detachListener)
        glass.close()
        tabHighlight?.close()
        content.close()
        sizing.close()
        ownership.close()
        glass.background.tag = null
        (glass.background.parent as? ViewGroup)?.removeView(glass.background)
        selectionOverlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
    }

    private companion object {
        const val LEASE_TAG = "qdhelper.glass-bars.session"
    }
}
