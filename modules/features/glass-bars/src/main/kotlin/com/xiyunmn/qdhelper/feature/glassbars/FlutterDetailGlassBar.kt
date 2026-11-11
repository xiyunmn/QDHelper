package com.xiyunmn.qdhelper.feature.glassbars

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.TextView
import com.xiyunmn.qdhelper.config.ConfigRuntime
import com.xiyunmn.qdhelper.feature.api.runtime.ActivityLifecycleCallbacks
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureResources
import com.xiyunmn.qdhelper.feature.api.runtime.FlutterBookDetailRuntime
import com.xiyunmn.qdhelper.feature.api.runtime.FlutterSemanticsSupport
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import java.lang.ref.WeakReference
import java.util.WeakHashMap

internal object FlutterDetailGlassBar {
    private val sessions = WeakHashMap<Activity, WeakReference<Session>>()
    private var lifecycle: AutoCloseable? = null
    private var configuration: AutoCloseable? = null
    private val main = Handler(Looper.getMainLooper())
    private var renderInstalled = false
    private val readLabels = setOf("立即阅读", "开始阅读", "继续阅读", "免费阅读", "会员免费读")
    private val shelfLabels = setOf("已在书架", "加入书架", "加书架")
    private val audioLabels = setOf("听书", "免费听书")
    private val controlLabels = readLabels + shelfLabels + audioLabels
    private val reported = WeakHashMap<Activity, MutableSet<String>>()

    private fun controlLabel(node: FlutterSemanticsSupport.Node): String? =
        node.label.lineSequence().map { line -> line.filterNot { it.isWhitespace() || it == '\u200B' } }
            .firstOrNull { it in controlLabels }

    private fun resolveControls(view: View, all: List<FlutterSemanticsSupport.Node>): List<FlutterSemanticsSupport.Node> {
        val byId = all.associateBy { it.id }
        val candidates = all.mapNotNull { text ->
            val caption = controlLabel(text) ?: return@mapNotNull null
            if (text.bounds.centerY() <= view.height * 3 / 4) return@mapNotNull null
            var target = text
            val visited = mutableSetOf<Int>()
            while (!target.clickable && target.parentId != null && visited.add(target.id)) {
                val parent = byId[target.parentId] ?: break
                if (parent.bounds.width() >= view.width * 3 / 4 || parent.bounds.height() > view.height / 5) break
                target = parent
            }
            if ((!target.clickable && caption != "已在书架" && caption !in readLabels) ||
                target.bounds.width() >= view.width * 3 / 4 || target.bounds.height() > view.height / 5 ||
                target.bounds.left < 0 || target.bounds.top < 0 || target.bounds.right > view.width ||
                target.bounds.bottom > view.height) return@mapNotNull null
            target.copy(label = caption)
        }.distinctBy { it.id }
        val read = candidates.singleOrNull { it.label in readLabels } ?: return emptyList()
        val row = candidates.filter {
            kotlin.math.abs(it.bounds.centerY() - read.bounds.centerY()) < maxOf(it.bounds.height(), read.bounds.height()) / 2
        }.sortedBy { it.bounds.left }
        return row.takeIf { it.size in 2..3 && it.count { node -> node.label in shelfLabels } <= 1 &&
            it.count { node -> node.label in audioLabels } <= 1 }.orEmpty()
    }

    private fun report(activity: Activity, state: String) {
        val seen = reported.getOrPut(activity) { mutableSetOf() }
        if (seen.size < 12 && seen.add(state)) FeatureHooks.i("Flutter detail glass: $state")
    }

    private fun enabled(activity: Activity) = activity.javaClass.name == FlutterBookDetailRuntime.ACTIVITY &&
        GlassBarKind.DETAIL in GlassBarsConfiguration.read(activity).enabled

    fun install(module: HookModule, classLoader: ClassLoader, phase: String, flutter: FlutterGlassBridge) {
        val activityClass = FeatureHooks.findClassOrNull("io.flutter.embedding.android.FlutterActivity", classLoader)
        val render = activityClass?.let { FeatureHooks.findDeclaredMethodOrNull(it, "getRenderMode") }
        val texture = render?.returnType?.enumConstants?.singleOrNull { (it as? Enum<*>)?.name == "texture" }
        if (texture == null) return
        if (!renderInstalled) {
            renderInstalled = FeatureHooks.hookAround(module, render, "glass.flutter-detail.render", "qdhelper.glass.flutter_detail.render") { chain ->
                val activity = chain.thisObject as? Activity
                if (activity != null && enabled(activity)) texture else chain.proceed()
            }
        }
        if (!renderInstalled) return
        FlutterSemanticsSupport.register(
            "glass-flutter-detail", ::enabled,
            updated = { activity, view, nodes ->
                val existing = sessions[activity]?.get()
                if (existing != null) existing.update(nodes)
                else Session.create(activity, view, nodes, flutter)?.let { sessions[activity] = WeakReference(it) }
            },
        )
        FlutterSemanticsSupport.install(module, classLoader, phase)
        if (lifecycle == null) lifecycle = ActivityLifecycleCallbacks.subscribe(
            OwnerId("glass-flutter-detail"), "qdhelper.glass.flutter_detail.lifecycle",
            resumed = { activity -> if (enabled(activity)) FlutterSemanticsSupport.refreshCurrentActivities() },
            windowFocusChanged = { activity, focused ->
                if (!focused) sessions[activity]?.get()?.focus(false)
                if (focused && enabled(activity)) FlutterSemanticsSupport.refreshCurrentActivities()
            },
            paused = { activity -> sessions[activity]?.get()?.focus(false) },
            destroyed = { activity -> sessions.remove(activity)?.get()?.close(); reported.remove(activity) },
            touchObserved = { activity, sample ->
                sessions[activity]?.get()?.touch(sample.action, sample.rawX, sample.rawY, sample.eventTimeMillis)
            },
        )
        val context = com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore.contextOrNull()
        if (configuration == null && context != null) configuration = ConfigRuntime.observeUserChanges(context, GlassBarsConfiguration.observedKeys) {
            main.post {
                sessions.keys.toList().forEach { activity ->
                    if (!enabled(activity)) sessions.remove(activity)?.get()?.close()
                }
                FlutterSemanticsSupport.refreshCurrentActivities()
            }
        }
        FeatureResources.register("glass-flutter-detail") {
            lifecycle?.close(); lifecycle = null
            configuration?.close(); configuration = null
            renderInstalled = false
            val release = Runnable { sessions.values.toList().forEach { it.get()?.close() }; sessions.clear(); reported.clear() }
            if (Looper.myLooper() == Looper.getMainLooper()) release.run() else main.postAtFrontOfQueue(release)
        }
        FlutterSemanticsSupport.refreshCurrentActivities()
    }

    private class Action(val view: View, val text: TextView, val labels: Set<String>) {
        var node: FlutterSemanticsSupport.Node? = null
    }

    private class Session(
        private val activity: Activity,
        private val source: View,
        private val parent: FrameLayout,
        private val bar: ViewGroup,
        private val actions: List<Action>,
        private val footerExtent: Int,
        private val flutter: FlutterGlassBridge,
        private val appearance: DetailGlassControls,
    ) : AutoCloseable {
        private var closed = false
        private var focused = activity.hasWindowFocus()
        private var coverPreview = false
        private var nodes = emptyList<FlutterSemanticsSupport.Node>()
        private val sourceHeight = OwnedProperty({ source.layoutParams.height }, { height ->
            source.layoutParams = source.layoutParams.apply { this.height = height }
        })
        private val parentLayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateViewport() }
        private val glass = GlassBarSession(
            GlassBarBinding(GlassBarKind.DETAIL, parent, bar, source,
                clearBackgrounds = listOf(bar), flexibleControl = actions.last().view, actionRow = bar),
            flutter,
        ) { reason -> report(activity, reason); parent.post { close() } }
        private val detach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) = close()
        }

        init {
            parent.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                bar.layoutParams.height, Gravity.BOTTOM))
            source.addOnAttachStateChangeListener(detach)
            parent.addOnLayoutChangeListener(parentLayout)
            actions.forEach { action ->
                action.view.setOnClickListener {
                    val previous = action.node ?: return@setOnClickListener
                    if (!enabled(activity) || !focused) return@setOnClickListener
                    val current = resolveControls(source, FlutterSemanticsSupport.nodes(activity))
                        .singleOrNull { it.id == previous.id && it.label == previous.label } ?: return@setOnClickListener
                    val accepted = FlutterSemanticsSupport.click(activity, current.id, touchWhenNoAction = current.label in readLabels)
                    report(activity, "dispatch label=${current.label}, touch=${!current.clickable}, accepted=$accepted")
                }
            }
            val config = GlassBarsConfiguration.read(activity)
            glass.attach(config.effects, false, config.dimensionsFor(GlassBarKind.DETAIL))
        }

        fun update(all: List<FlutterSemanticsSupport.Node>) {
            if (closed) return
            if (!enabled(activity)) { close(); return }
            val controls = resolveControls(source, all)
            if (FlutterBookDetailRuntime.coverPreviewClose(source, all) != null) coverPreview = true
            else if (controls.isNotEmpty()) coverPreview = false
            // Keep the bar hidden through transient empty semantics until detail controls return.
            nodes = if (coverPreview) emptyList() else controls
            bar.visibility = if (coverPreview) View.INVISIBLE else View.VISIBLE
            updateViewport()
            if (nodes.isEmpty()) {
                actions.forEach { it.node = null }
            } else actions.forEach { action ->
                val node = nodes.singleOrNull { it.label in action.labels }
                action.node = node
                action.view.visibility = if (node == null) View.GONE else View.VISIBLE
                if (node != null) {
                    action.text.text = node.label
                    action.view.isEnabled = node.clickable || node.label in readLabels
                    if (node.label in shelfLabels) appearance.setJoined(node.label == "已在书架")
                }
            }
            appearance.applyTheme()
            focus(activity.hasWindowFocus())
            val config = GlassBarsConfiguration.read(activity)
            glass.update(config.effects, config.dimensionsFor(GlassBarKind.DETAIL))
            if (nodes.isNotEmpty()) report(activity, "module controls bound=${nodes.joinToString { it.label }}")
        }

        fun focus(value: Boolean) {
            if (closed) return
            focused = value
            // Focus controls live sampling and input; the page retains its last rendered bar.
            glass.setActive(value && nodes.isNotEmpty())
        }

        fun touch(action: Int, x: Float, y: Float, time: Long) {
            if (!closed && focused && nodes.isNotEmpty()) glass.observeTouch(action, x, y, time)
        }

        private fun updateViewport() {
            if (closed) return
            // Anchor to the unchanged window container, never to our previously expanded Flutter height.
            val viewport = parent.height - parent.paddingBottom - source.top
            if (viewport > 0) sourceHeight.set(viewport + footerExtent)
        }


        override fun close() {
            if (closed) return
            closed = true
            source.removeOnAttachStateChangeListener(detach)
            parent.removeOnLayoutChangeListener(parentLayout)
            glass.close()
            sourceHeight.close()
            parent.removeView(bar)
            if (sessions[activity]?.get() === this) sessions.remove(activity)
        }

        companion object {
            fun create(activity: Activity, source: View, nodes: List<FlutterSemanticsSupport.Node>, flutter: FlutterGlassBridge): Session? {
                val parent = source.parent as? FrameLayout ?: return null
                val group = source as? ViewGroup ?: return null
                if ((0 until group.childCount).any { group.getChildAt(it) is android.view.SurfaceView }) {
                    report(activity, "waiting texture surface")
                    return null
                }
                val controls = resolveControls(source, nodes)
                if (controls.isEmpty()) return null
                val appearance = DetailGlassControls(activity)
                val actions = listOf(
                    Action(appearance.shelf.view, appearance.shelf.text, shelfLabels),
                    Action(appearance.audio.view, appearance.audio.text, audioLabels),
                    Action(appearance.read.view, appearance.read.text, readLabels),
                )
                val nav = source.rootWindowInsets?.getInsets(WindowInsets.Type.navigationBars())?.bottom ?: 0
                val topPadding = (source.height - controls.maxOf { it.bounds.bottom } - nav).coerceAtLeast(0)
                val footerExtent = source.height - controls.minOf { it.bounds.top } + topPadding
                if (footerExtent <= 0 || footerExtent > source.height / 5) return null
                report(activity, "footer extent=$footerExtent, padding=$topPadding, viewport=${parent.height}")
                return Session(activity, source, parent, appearance.bar, actions, footerExtent, flutter, appearance)
                    .also { it.update(nodes) }.takeUnless { it.closed }
            }
        }
    }
}
