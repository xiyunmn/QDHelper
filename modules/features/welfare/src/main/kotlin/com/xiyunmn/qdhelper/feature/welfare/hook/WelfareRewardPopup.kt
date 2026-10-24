package com.xiyunmn.qdhelper.feature.welfare.hook

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.xiyunmn.qdhelper.config.ConfigRuntime
import com.xiyunmn.qdhelper.config.SettingIds
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.feature.api.runtime.ActivityLifecycleCallbacks
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureResources
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import com.xiyunmn.qdhelper.feature.welfare.action.welfareRewardPopupScript
import com.xiyunmn.qdhelper.feature.welfare.host.WelfareWebViewHost
import com.xiyunmn.qdhelper.feature.welfare.model.isWelfareCenterUrl
import com.xiyunmn.qdhelper.feature.welfare.model.unwrapActivity
import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.host.api.target.HostTargetResolver
import com.xiyunmn.qdhelper.host.api.target.WelfareTargetKeys
import java.lang.ref.WeakReference

/** Popup presentation is independent of whether the user skips reward videos. */
internal object WelfareRewardPopup {
    private var runtime: PopupRuntime? = null
    init {
        FeatureResources.register("welfare.reward-popup") {
            synchronized(this) { runtime?.close(); runtime = null }
        }
    }

    @Synchronized
    fun install(module: HookModule, classLoader: ClassLoader): Int {
        if (runtime != null) return 0
        val context = HostSessionStore.contextOrNull() ?: return 0
        val host = WelfareWebViewHost.resolve(classLoader) ?: return 0
        val finished = HostTargetResolver.resolveMethod(context, classLoader, HostSessionStore.processName,
            WelfareTargetKeys.browserPageFinished) ?: return 0
        val candidate = PopupRuntime(host)
        val installed = FeatureHooks.hookAfter(module, finished, "welfare.reward-popup", "welfare.popup.page-finished") { chain, _ ->
            host.webView(chain.thisObject)?.let { candidate.update(it, "page-finished") }
        }
        if (!installed) { candidate.close(); return 0 }
        candidate.lifecycle = ActivityLifecycleCallbacks.subscribe(
            OwnerId("welfare"), "welfare.popup.lifecycle",
            resumed = { candidate.resume(it) }, destroyed = { candidate.destroy(it) },
        )
        candidate.settings = ConfigRuntime.observeUserChanges(context, setOf(
            SettingIds.KEY_MODULE_ENABLED, SettingIds.KEY_WELFARE_REWARD_POPUP_SUPPRESS_ENABLED,
        )) { candidate.refresh() }
        runtime = candidate
        candidate.restoreCurrentPage()
        return 1
    }

    fun resume(activity: Activity?, reason: String) { activity?.let { runtime?.resume(it, reason) } }
}

private class PopupRuntime(private val host: WelfareWebViewHost) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private val views = mutableListOf<WeakReference<View>>()
    var lifecycle: AutoCloseable? = null
    var settings: AutoCloseable? = null
    @Volatile private var closed = false
    private var failureReported = false

    fun restoreCurrentPage() {
        handler.post {
            if (closed) return@post
            ActivityLifecycleCallbacks.currentActivities().filter { it.hasWindowFocus() }
                .forEach { resume(it, "install") }
        }
    }

    fun resume(activity: Activity, reason: String = "resume") {
        handler.post {
            if (closed || activity.isFinishing || activity.isDestroyed) return@post
            visit(activity.window?.decorView) { update(it, reason) }
        }
    }

    private fun visit(view: View?, action: (View) -> Unit) {
        if (view == null) return
        if (host.isWebView(view)) { action(view); return }
        if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index), action)
    }

    fun update(view: View, reason: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) { handler.post { update(view, reason) }; return }
        if (closed || !view.isAttachedToWindow || !host.url(view).isWelfareCenterUrl()) return
        views.removeAll { it.get() == null }
        if (views.none { it.get() === view }) views += WeakReference(view)
        evaluate(view, SettingsRuntime.isWelfareRewardPopupSuppressed(view.context), reason)
    }

    private fun evaluate(view: View, enabled: Boolean, reason: String) {
        val scheduled = runCatching {
            host.evaluate(view, welfareRewardPopupScript(enabled)) { result ->
                if (!closed && enabled) FeatureHooks.i("Welfare reward popup: reason=$reason, result=$result")
            }
        }.getOrDefault(false)
        if (!scheduled && !closed && !failureReported) {
            failureReported = true
            FeatureHooks.w("Welfare reward popup: JavaScript evaluation unavailable, reason=$reason")
        }
    }

    fun refresh() { handler.post { if (!closed) views.mapNotNull { it.get() }.forEach { update(it, "setting") } } }

    fun destroy(activity: Activity) {
        views.removeAll { reference ->
            val view = reference.get()
            if (view?.context?.unwrapActivity() === activity) { evaluate(view, false, "destroy"); true } else view == null
        }
    }

    override fun close() {
        closed = true
        lifecycle?.close(); settings?.close()
        handler.removeCallbacksAndMessages(null)
        val cleanup = Runnable {
            views.mapNotNull { it.get() }.forEach { evaluate(it, false, "dispose") }
            views.clear()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) cleanup.run() else handler.post(cleanup)
    }
}
