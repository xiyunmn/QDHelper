package com.xiyunmn.qdhelper.feature.glassbars

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import com.xiyunmn.qdhelper.config.ConfigRuntime
import com.xiyunmn.qdhelper.feature.api.runtime.ActivityLifecycleCallbacks
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureResources
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import com.xiyunmn.qdhelper.feature.api.runtime.InstallableFeature
import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import java.lang.ref.WeakReference
import java.util.WeakHashMap

object GlassBarsFeature : InstallableFeature {
    override val name = "GlassBarsFeature"
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    // Values must also be weak: a controller owns views whose Context points back to the key.
    private val controllers = WeakHashMap<Activity, WeakReference<GlassActivityController>>()
    private var lifecycle: AutoCloseable? = null
    private var settings: AutoCloseable? = null
    private var flutter: FlutterGlassBridge? = null
    private var configuration = GlassBarsConfiguration()
    @Volatile private var disposed = false
    private val refresh = Runnable {
        if (!disposed) {
            configuration = GlassBarsConfiguration.read(HostSessionStore.contextOrNull())
            controllers.values.toList().forEach { it.get()?.apply(configuration) }
        }
    }

    override fun install(module: HookModule, classLoader: ClassLoader, processName: String, phase: String) {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        if (!HostSymbols.isMainProcess(processName)) return
        disposed = false
        GlassNativeTabAppearance.install(module, classLoader)
        val bridge = flutter ?: FlutterGlassBridge(::engineAttached).also { flutter = it }
        val wasAvailable = bridge.available
        if (!wasAvailable) bridge.install(module, classLoader)
        if (lifecycle == null) {
            lifecycle = ActivityLifecycleCallbacks.subscribe(
                owner = OwnerId("glass-bars"), id = "qdhelper.glass_bars.activities", priority = -100,
                created = ::applyFromSettings,
                resumed = { activity -> applyFromSettings(activity); controller(activity)?.focus(activity.hasWindowFocus()) },
                windowFocusChanged = { activity, focused -> applyFromSettings(activity); controller(activity)?.focus(focused) },
                paused = { controller(it)?.focus(false) },
                destroyed = { controllers.remove(it)?.get()?.close() },
                touchObserved = { activity, sample -> controller(activity)?.touch(sample.action, sample.rawX, sample.rawY, sample.eventTimeMillis) },
            )
        }
        FeatureResources.register("glass-bars") { dispose() }
        handler.post {
            if (!disposed) {
                HostSessionStore.contextOrNull()?.let { context -> observe(context) }
                if (!wasAvailable && bridge.available) controllers.values.toList().forEach { it.get()?.retryBinding() }
                ActivityLifecycleCallbacks.currentActivities().forEach(::applyFromSettings)
            }
        }
    }

    fun applyFromSettings(activity: Activity) {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        if (disposed) return
        val adapter = adapter(activity) ?: return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { applyFromSettings(activity) }
            return
        }
        observe(activity)
        configuration = GlassBarsConfiguration.read(activity)
        val bridge = flutter ?: return
        val controller = controller(activity) ?: GlassActivityController(activity, adapter, bridge).also {
            controllers[activity] = WeakReference(it)
        }
        controller.apply(configuration)
    }

    private fun observe(context: android.content.Context) {
        if (settings == null) {
            settings = ConfigRuntime.observeUserChanges(context, GlassBarsConfiguration.observedKeys) {
                handler.removeCallbacks(refresh)
                handler.post(refresh)
            }
        }
    }

    private fun adapter(activity: Activity): GlassBarAdapter? = when (activity.javaClass.name) {
        HostSymbols.Classes.MAIN_GROUP_ACTIVITY -> MainGlassBarAdapter
        HostSymbols.Classes.BOOK_DETAIL_ACTIVITY -> DetailGlassBarAdapter
        HostSymbols.Classes.QD_DIRECTORY_ACTIVITY -> DirectoryGlassBarAdapter
        else -> null
    }

    private fun controller(activity: Activity) = controllers[activity]?.get()

    private fun engineAttached(view: View) {
        if (!disposed) controllers.values.toList().forEach { it.get()?.engineAttached(view) }
    }

    private fun dispose() {
        disposed = true
        lifecycle?.close()
        lifecycle = null
        settings?.close()
        settings = null
        val retiringBridge = flutter
        flutter = null
        handler.removeCallbacksAndMessages(null)
        val release = Runnable {
            controllers.values.toList().forEach { it.get()?.close() }
            controllers.clear()
            GlassNativeTabAppearance.clear()
            retiringBridge?.close()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) release.run() else handler.postAtFrontOfQueue(release)
    }
}
