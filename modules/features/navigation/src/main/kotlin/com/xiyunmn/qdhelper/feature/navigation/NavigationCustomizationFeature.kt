package com.xiyunmn.qdhelper.feature.navigation

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.feature.api.runtime.ActivityLifecycleCallbacks
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureResources
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import com.xiyunmn.qdhelper.feature.api.runtime.InstallableFeature
import com.xiyunmn.qdhelper.feature.api.runtime.UiFeatureSupport
import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols

object NavigationCustomizationFeature : InstallableFeature {
    override val name: String = "NavigationCustomizationFeature"
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val subscriptions = mutableListOf<AutoCloseable>()

    init {
        FeatureResources.register("navigation-customization") {
            synchronized(subscriptions) {
                subscriptions.forEach { runCatching(it::close) }
                subscriptions.clear()
            }
            mainHandler.removeCallbacksAndMessages(null)
            if (Looper.myLooper() == Looper.getMainLooper()) BottomTabCleaner.closeLayouts()
            else mainHandler.post { BottomTabCleaner.closeLayouts() }
        }
    }

    override fun install(
        module: HookModule,
        classLoader: ClassLoader,
        processName: String,
        phase: String,
    ) {
        var installedHooks = 0
        installedHooks += BottomTabCleaner.install(module, classLoader, phase)
        subscribeActivityCallbacks()
        ActivityLifecycleCallbacks.currentActivities().forEach { activity ->
            activity.runOnUiThread { applyIfMainActivity(activity) }
        }
        FeatureHooks.i(
            "Navigation customization phase finished: phase=$phase, process=$processName, installedHooks=$installedHooks",
        )
    }

    fun applyFromSettings(activity: Activity) {
        applyFromOwner(activity)
    }

    private fun subscribeActivityCallbacks() {
        synchronized(subscriptions) {
            if (subscriptions.isNotEmpty()) return
            ActivityLifecycleCallbacks.subscribe(
                owner = OwnerId("navigation"),
                id = "qdhelper.ui.activity_apply_on_resume",
                resumed = ::applyIfMainActivity,
            )?.let(subscriptions::add)
            ActivityLifecycleCallbacks.subscribe(
                owner = OwnerId("navigation"),
                id = "qdhelper.ui.activity_apply_on_focus",
                windowFocusChanged = { activity, hasFocus -> if (hasFocus) applyIfMainActivity(activity) },
            )?.let(subscriptions::add)
        }
    }

    private fun applyIfMainActivity(activity: Activity) {
        if (activity.javaClass.name == HostSymbols.Classes.MAIN_GROUP_ACTIVITY) {
            applyFromOwner(activity)
        }
    }

    private fun applyFromOwner(owner: Any?): Boolean {
        val root = UiFeatureSupport.rootViewOf(owner) ?: return false
        scheduleApplyIfNeeded(root)
        return apply(root)
    }

    private fun scheduleApplyIfNeeded(root: View) {
        val context = HostSessionStore.contextOrNull()
        if (!SettingsRuntime.isHomeTopActionCustomEnabled(context) &&
            !SettingsRuntime.isBottomTabCustomEnabled(context) &&
            !SettingsRuntime.isBottomTabHideActivityEnabled(context) &&
            !SettingsRuntime.isBottomTabRedDotHidden(context) &&
            !SettingsRuntime.isAccountTabRedDotHidden(context)
        ) {
            return
        }
        root.post { apply(root) }
        mainHandler.postDelayed({ apply(root) }, 300L)
        mainHandler.postDelayed({ apply(root) }, 1_200L)
    }

    private fun apply(root: View): Boolean {
        var changed = BottomTabCleaner.refreshVisibleBadges(root)
        changed = HomeTopActionCustomization.applyHomeTopActionCustom(root) || changed
        changed = BottomTabCleaner.applyBottomTabCustom(root) || changed
        return changed
    }
}
