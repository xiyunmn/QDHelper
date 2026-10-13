package com.xiyunmn.qdhelper.feature.glassbars

import android.view.View
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import java.lang.ref.WeakReference

/** Stable Flutter embedding APIs, verified statically on all five active host versions. */
internal class FlutterGlassBridge(private val attachmentChanged: (View) -> Unit) : AutoCloseable {
    private val overrides = FlutterViewportOverrides()
    private val views = mutableListOf<WeakReference<View>>()
    var available = false
        private set

    fun install(module: HookModule, loader: ClassLoader) {
        val rendererClass = FeatureHooks.findClassOrNull(HostSymbols.Classes.FLUTTER_RENDERER, loader) ?: return
        val metricsClass = FeatureHooks.findClassOrNull(HostSymbols.Classes.FLUTTER_VIEWPORT_METRICS, loader) ?: return
        val viewClass = FeatureHooks.findClassOrNull(HostSymbols.Classes.FLUTTER_VIEW, loader) ?: return
        val engineClass = FeatureHooks.findClassOrNull(HostSymbols.Classes.FLUTTER_ENGINE, loader) ?: return
        val setter = FeatureHooks.findDeclaredMethodOrNull(rendererClass, "setViewportMetrics", metricsClass) ?: return
        val field = FeatureHooks.findFieldOrNull(metricsClass, "viewPaddingBottom") ?: return
        if (field.type != Int::class.javaPrimitiveType) return
        available = FeatureHooks.hookAround(module, setter, "glass-bars.flutter-metrics", "qdhelper.glass_bars.flutter.metrics") { chain ->
            val renderer = chain.thisObject ?: return@hookAround chain.proceed()
            val bottom = overrides.bottomFor(renderer) ?: return@hookAround chain.proceed()
            val metrics = chain.getArg(0) ?: return@hookAround chain.proceed()
            val original = FeatureHooks.read(field, metrics) as? Int ?: return@hookAround chain.proceed()
            withFlutterBottomPadding(bottom, { original }, { FeatureHooks.write(field, metrics, it) }) { chain.proceed() }
        }
        FeatureHooks.hookAfter(
            module, FeatureHooks.findDeclaredMethodOrNull(viewClass, "attachToFlutterEngine", engineClass),
            "glass-bars.flutter-attached", "qdhelper.glass_bars.flutter.attached",
        ) { chain, _ -> (chain.thisObject as? View)?.let(attachmentChanged) }
        FeatureHooks.hookAfter(
            module, FeatureHooks.findDeclaredMethodOrNull(viewClass, "detachFromFlutterEngine"),
            "glass-bars.flutter-detached", "qdhelper.glass_bars.flutter.detached",
        ) { chain, _ -> (chain.thisObject as? View)?.let(::remove) }
    }

    fun update(view: View, bottom: Int) {
        if (!available || !view.isAttachedToWindow) return
        val engine = FeatureHooks.invokeNoArgOrNull(view, "getAttachedFlutterEngine") ?: return
        val renderer = FeatureHooks.invokeNoArgOrNull(engine, "getRenderer") ?: return
        if (overrides.put(renderer, view, bottom)) {
            views.removeAll { it.get() == null }
            if (views.none { it.get() === view }) views += WeakReference(view)
            view.requestApplyInsets()
        }
    }

    fun remove(view: View) {
        overrides.removeView(view)
        views.removeAll { it.get() == null || it.get() === view }
        // No module padding is left in FlutterView's metrics. Re-send the original window insets.
        if (view.isAttachedToWindow) view.requestApplyInsets()
    }

    override fun close() {
        overrides.clear()
        views.forEach { it.get()?.takeIf(View::isAttachedToWindow)?.requestApplyInsets() }
        views.clear()
        available = false
    }
}
