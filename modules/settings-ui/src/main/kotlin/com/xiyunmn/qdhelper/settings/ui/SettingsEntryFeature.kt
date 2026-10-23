package com.xiyunmn.qdhelper.settings.ui

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.xiyunmn.qdhelper.settings.ui.compose.host.ComposeSettingsPanel
import com.xiyunmn.qdhelper.feature.api.runtime.ActivityLifecycleCallbacks
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureResources
import com.xiyunmn.qdhelper.feature.api.runtime.InstallableFeature
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols as QDReaderHost
import com.xiyunmn.qdhelper.hook.view.LongPressRegistration
import com.xiyunmn.qdhelper.hook.view.LongPressRegistrationResult
import com.xiyunmn.qdhelper.hook.view.LongPressResult
import com.xiyunmn.qdhelper.feature.api.runtime.ViewLongPressCallbacks
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Installs the host long-press settings entry point. The actual
 * lifecycle hooks are installed once by the application Activity callback hub;
 * this unit only subscribes and binds host views.
 */
object SettingsEntryFeature : InstallableFeature {
    override val name: String = "SettingsEntryFeature"

    private val entryViewIds = listOf("ivSetting", "ivMoreView")
    private data class BoundEntry(val owner: WeakReference<Activity>, val registration: AutoCloseable)
    private val boundViews: MutableMap<View, BoundEntry> = WeakHashMap()
    private val installed = AtomicBoolean(false)

    override fun install(
        module: HookModule,
        classLoader: ClassLoader,
        processName: String,
        phase: String,
    ) {
        if (!installed.compareAndSet(false, true)) {
            return
        }
        val registration = ActivityLifecycleCallbacks.subscribe(
            owner = OwnerId("settings-ui"),
            id = "settings.entry.lifecycle",
            priority = 100,
            created = { activity -> attachIfTarget(activity) },
            destroyed = { activity -> releaseEntries(activity) },
            resumed = { activity -> attachIfTarget(activity) },
            windowFocusChanged = { activity, hasFocus ->
                if (hasFocus) attachIfTarget(activity)
            },
        )
        if (registration == null) {
            installed.set(false)
            FeatureHooks.w("Settings entry lifecycle bridge unavailable: phase=$phase, process=$processName")
            return
        }
        FeatureResources.register("settings-entry") {
            ComposeSettingsPanel.closeForHotReload()
            runCatching { registration.close() }
            val registrations = synchronized(boundViews) { boundViews.values.map { it.registration }.also { boundViews.clear() } }
            registrations.forEach { runCatching { it.close() } }
            installed.set(false)
        }
        FeatureHooks.i("Settings host long-press entry subscribed: phase=$phase, process=$processName")
    }

    private fun releaseEntries(activity: Activity) {
        val registrations = synchronized(boundViews) {
            val matches = boundViews.entries.filter { it.value.owner.get() === activity }
            matches.map { it.value.registration }.also { matches.forEach { entry -> boundViews.remove(entry.key) } }
        }
        registrations.forEach { runCatching(it::close) }
    }

    private fun attachIfTarget(activity: Activity?) {
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            return
        }
        if (activity.javaClass.name != QDReaderHost.Classes.MAIN_GROUP_ACTIVITY) {
            return
        }

        val decor = activity.window?.decorView as? ViewGroup ?: return
        val activityRef = WeakReference(activity)
        val decorRef = WeakReference(decor)
        listOf(0L, 180L, 600L, 1200L).forEach { delay ->
            decor.postDelayed({
                val owner = activityRef.get()
                val root = decorRef.get()
                if (installed.get() && owner != null && root != null && !owner.isFinishing && !owner.isDestroyed) {
                    bindLongPressEntries(owner, root)
                }
            }, delay)
        }
    }

    private fun bindLongPressEntries(activity: Activity, root: View) {
        var boundCount = 0
        entryViewIds.forEach { idName ->
            findChildViewsByIdName(root, idName).forEach { view ->
                synchronized(boundViews) {
                    if (boundViews.containsKey(view)) return@forEach
                }
                val ownerRef = WeakReference(activity)
                val result = ViewLongPressCallbacks.register(
                    target = view,
                    registration = LongPressRegistration(
                        owner = OwnerId("settings-ui"),
                        id = "settings.entry.${idName}",
                        priority = 100,
                    ),
                ) { target, _ ->
                    val owner = ownerRef.get() ?: return@register LongPressResult.Pass
                    val targetView = target as? View ?: return@register LongPressResult.Pass
                    if (!owner.isFinishing && !owner.isDestroyed) {
                        // The central long-press router short-circuits View.performLongClick,
                        // so reproduce the host's normal long-press触感反馈 here.
                        runCatching {
                            targetView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        }
                        FeatureHooks.i("Settings open requested: activity=${QDReaderHost.classLabel(owner.javaClass.name)}")
                        runCatching { ComposeSettingsPanel.showSettings(owner) }.onFailure { error ->
                            FeatureHooks.w("Settings open failed: ${error.javaClass.name}")
                            Toast.makeText(owner, "设置打开失败，请重试", Toast.LENGTH_SHORT).show()
                        }
                    }
                    LongPressResult.Return(true)
                }
                if (result is LongPressRegistrationResult.Registered) {
                    synchronized(boundViews) { boundViews[view] = BoundEntry(ownerRef, result.registration) }
                    boundCount += 1
                } else {
                    FeatureHooks.w("Settings long-press registration skipped: id=$idName")
                }
            }
        }
        if (boundCount > 0) {
            FeatureHooks.i(
                "Settings host long-press entry bound: count=$boundCount, " +
                    "activity=${QDReaderHost.classLabel(activity.javaClass.name)}",
            )
        }
    }

    private fun findChildViewsByIdName(root: View, idName: String): List<View> {
        val resources = root.resources
        val packageName = root.context.packageName
        val id = resources.getIdentifier(idName, "id", packageName)
        if (id == 0) return emptyList()
        val matches = ArrayList<View>()
        fun traverse(view: View) {
            if (view.id == id) {
                matches += view
            }
            if (view !is ViewGroup) return
            for (index in 0 until view.childCount) {
                traverse(view.getChildAt(index))
            }
        }
        traverse(root)
        return matches
    }
}
