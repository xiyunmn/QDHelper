package com.xiyunmn.qdhelper.feature.welfare.hook

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.welfare.action.welfareArmReceiptScript
import com.xiyunmn.qdhelper.feature.welfare.action.welfareReadReceiptScript
import com.xiyunmn.qdhelper.feature.welfare.action.welfareSyncPageScript
import com.xiyunmn.qdhelper.feature.welfare.host.WelfareRecoveryHost
import com.xiyunmn.qdhelper.feature.welfare.model.WelfareClaimPath
import com.xiyunmn.qdhelper.feature.welfare.model.WelfareClaimState
import com.xiyunmn.qdhelper.feature.welfare.model.WelfarePageState
import com.xiyunmn.qdhelper.feature.welfare.model.WelfareRecoveryDecision
import com.xiyunmn.qdhelper.feature.welfare.model.isWelfareCenterUrl
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

internal class WelfareRecoveryRequest(
    val state: WelfareClaimState,
    val page: WelfareRecoveryPage,
    plugin: Any,
    config: Any,
    val account: String,
    val url: String,
) {
    val plugin = WeakReference(plugin)
    val config = WeakReference(config)
    var check: Runnable? = null
    var timeout: Runnable? = null
    var receiptTimeout: Runnable? = null
    var readingReceipt = false
}

internal class WelfareRecoveryPage(val id: Long, activity: Activity, view: View) {
    val activity = WeakReference(activity)
    val view = WeakReference(view)
    val state = WelfarePageState()
    @Volatile var request: WelfareRecoveryRequest? = null
    var paused = false
    var attachListener: View.OnAttachStateChangeListener? = null
    var focusListener: ViewTreeObserver.OnWindowFocusChangeListener? = null
}

internal data class WelfareClaimStart(val request: WelfareRecoveryRequest? = null, val suppress: Boolean = false)

/** All WebView work runs on the main thread; worker callbacks carry their original request. */
internal class WelfarePageRecovery(
    private val host: WelfareRecoveryHost,
    private val enabled: () -> Boolean,
) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private val ids = AtomicLong()
    private val pages = CopyOnWriteArrayList<WelfareRecoveryPage>()
    private var application: Application? = null
    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            pages.filter { it.activity.get() === activity }.forEach { page ->
                page.paused = false
                page.request?.let {
                    if (it.timeout == null) armTimeout(it)
                    changed(it, "page_resumed")
                }
            }
        }
        override fun onActivityPaused(activity: Activity) {
            pages.filter { it.activity.get() === activity }.forEach { page ->
                page.paused = true
                page.request?.check?.let(handler::removeCallbacks)
            }
        }
        override fun onActivityDestroyed(activity: Activity) {
            pages.filter { it.activity.get() === activity }.forEach(::removePage)
        }
        override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    }

    fun begin(
        plugin: Any,
        activity: Activity,
        config: Any,
        callbackId: Int,
        path: WelfareClaimPath,
    ): WelfareClaimStart {
        if (!enabled() || Looper.myLooper() != Looper.getMainLooper() || activity.isFinishing || activity.isDestroyed) {
            return WelfareClaimStart()
        }
        val candidates = findWebViews(activity.window?.decorView).filter { host.url(it).isWelfareCenterUrl() }
        val view = candidates.singleOrNull() ?: return WelfareClaimStart()
        val url = host.url(view) ?: return WelfareClaimStart()
        val account = host.account() ?: return WelfareClaimStart()
        val page = pages.singleOrNull { it.view.get() === view } ?: addPage(activity, view)
        val previous = page.request
        if (previous != null && (previous.account != account || previous.url != url)) cancel(page)
        val state = page.state.begin(ids.incrementAndGet(), callbackId, path)
        if (state == null) {
            previous?.let { changed(it, "duplicate_entry_suppressed") }
            return WelfareClaimStart(suppress = true)
        }
        val request = WelfareRecoveryRequest(state, page, plugin, config, account, url)
        page.request = request
        host.evaluate(view, welfareArmReceiptScript(state.id, callbackId, url)) { armed ->
            trace(request, if (armed == "true") "receipt_armed" else "receipt_arm_unavailable")
        }
        armTimeout(request)
        trace(request, "begin")
        return WelfareClaimStart(request)
    }

    fun byConfig(config: Any?): WelfareRecoveryRequest? = config?.let {
        pages.mapNotNull { page -> page.request }
            .filter { it.config.get() === config && it.page.state.isCurrent(it.state) }.singleOrNull()
    }

    fun byActivity(activity: Activity?): WelfareRecoveryRequest? = activity?.let {
        pages.filter { it.activity.get() === activity }.mapNotNull { it.request }
            .filter { it.page.state.isCurrent(it.state) }.singleOrNull()
    }

    fun changed(request: WelfareRecoveryRequest, event: String) {
        trace(request, event)
        handler.post {
            if (!request.page.state.isCurrent(request.state)) return@post
            request.check?.let(handler::removeCallbacks)
            request.check = Runnable { check(request) }.also { handler.postDelayed(it, CALLBACK_SETTLE_MS) }
        }
    }

    fun claimStarted(request: WelfareRecoveryRequest) {
        handler.post {
            if (request.page.state.isCurrent(request.state)) armTimeout(request)
        }
    }

    private fun armTimeout(request: WelfareRecoveryRequest) {
        request.timeout?.let(handler::removeCallbacks)
        val revision = request.state.waitRevision()
        request.timeout = Runnable {
            request.timeout = null
            // Time spent in a separate ad window cannot expire a just-started reward request.
            if (valid(request, foreground = true) && request.state.timeout(revision)) {
                changed(request, "wait_timeout")
            }
        }.also { handler.postDelayed(it, CLAIM_TIMEOUT_MS) }
    }

    fun pageStarted(fragment: Any?) {
        val view = host.webView(fragment) ?: return
        val page = pages.singleOrNull { it.view.get() === view } ?: return
        page.request?.let {
            trace(it, "document_left")
            cancelTimers(it)
        }
        page.state.pageStarted()
        page.request = null
    }

    fun valid(request: WelfareRecoveryRequest, foreground: Boolean = false): Boolean {
        if (!request.page.state.isCurrent(request.state)) return false
        val activity = request.page.activity.get() ?: return false
        val view = request.page.view.get() ?: return false
        if (activity.isFinishing || activity.isDestroyed || !view.isAttachedToWindow) return false
        if (foreground && (request.page.paused || !activity.hasWindowFocus())) return false
        return host.account() == request.account && host.url(view) == request.url
    }

    private fun check(request: WelfareRecoveryRequest) {
        if (!request.page.state.isCurrent(request.state)) return
        if (!valid(request)) { cancel(request.page); return }
        if (!valid(request, foreground = true) || request.readingReceipt) return
        val decision = request.state.decision()
        if (decision != WelfareRecoveryDecision.CHECK_CALLBACK) return
        val view = request.page.view.get() ?: return
        request.readingReceipt = true
        val receiptTimeout = Runnable {
            if (request.readingReceipt && request.page.state.isCurrent(request.state)) {
                request.readingReceipt = false
                request.receiptTimeout = null
                trace(request, "receipt_unavailable")
                cancel(request.page)
            }
        }
        request.receiptTimeout = receiptTimeout
        handler.postDelayed(receiptTimeout, RECEIPT_TIMEOUT_MS)
        val scheduled = host.evaluate(view, welfareReadReceiptScript(request.state.id, request.url)) { raw ->
            handler.post {
                handler.removeCallbacks(receiptTimeout)
                request.receiptTimeout = null
                request.readingReceipt = false
                if (!valid(request, foreground = true)) return@post
                val receipt = runCatching { JSONObject(raw ?: "") }.getOrNull()
                if (receipt == null || receipt.optLong("id", -1) != request.state.id) {
                    trace(request, "document_changed_or_receipt_unavailable")
                    cancel(request.page)
                    return@post
                }
                val received = receipt.optBoolean("received", false)
                trace(request, "callback_receipt_$received")
                when (request.state.decision(received)) {
                    WelfareRecoveryDecision.FINISHED -> {
                        if (request.state.finishWithReceipt()) {
                            cancelTimers(request)
                            trace(request, "host_callback_completed")
                        } else {
                            changed(request, "claim_changed_during_receipt")
                        }
                    }
                    WelfareRecoveryDecision.SYNC_PAGE -> syncPage(request)
                    else -> Unit
                }
            }
        }
        if (!scheduled) {
            handler.removeCallbacks(receiptTimeout)
            request.receiptTimeout = null
            request.readingReceipt = false
            trace(request, "receipt_evaluation_failed")
            cancel(request.page)
        }
    }

    private fun syncPage(request: WelfareRecoveryRequest) {
        if (!valid(request, foreground = true)) return
        val view = request.page.view.get() ?: return
        if (!request.state.reservePageSync()) return
        cancelTimers(request)
        trace(request, "page_sync_reserved")
        val scheduled = host.evaluate(view, welfareSyncPageScript(request.state.id, request.url)) { result ->
            trace(request, if (result == "true") "page_sync_dispatched" else "page_sync_unavailable")
        }
        if (!scheduled) trace(request, "page_sync_evaluation_failed")
    }

    private fun addPage(activity: Activity, view: View): WelfareRecoveryPage {
        if (application == null) {
            application = activity.application.also { it.registerActivityLifecycleCallbacks(lifecycle) }
        }
        val page = WelfareRecoveryPage(ids.incrementAndGet(), activity, view)
        page.attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) = removePage(page)
        }.also(view::addOnAttachStateChangeListener)
        page.focusListener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if (focused) page.request?.let {
                if (it.timeout == null) armTimeout(it)
                changed(it, "page_focused")
            }
        }.also(view.viewTreeObserver::addOnWindowFocusChangeListener)
        pages += page
        return page
    }

    private fun findWebViews(root: View?): List<View> = when {
        root == null -> emptyList()
        host.isWebView(root) -> listOf(root)
        root is ViewGroup -> (0 until root.childCount).flatMap { findWebViews(root.getChildAt(it)) }
        else -> emptyList()
    }

    private fun cancelTimers(request: WelfareRecoveryRequest) {
        request.check?.let(handler::removeCallbacks)
        request.timeout?.let(handler::removeCallbacks)
        request.receiptTimeout?.let(handler::removeCallbacks)
        request.check = null
        request.timeout = null
        request.receiptTimeout = null
        request.readingReceipt = false
    }

    private fun cancel(page: WelfareRecoveryPage) {
        page.request?.let(::cancelTimers)
        page.state.cancel()
        page.request = null
    }

    private fun removePage(page: WelfareRecoveryPage) {
        cancel(page)
        page.view.get()?.let { view ->
            page.attachListener?.let(view::removeOnAttachStateChangeListener)
            if (view.viewTreeObserver.isAlive) page.focusListener?.let(view.viewTreeObserver::removeOnWindowFocusChangeListener)
        }
        pages.remove(page)
    }

    fun trace(request: WelfareRecoveryRequest, event: String) {
        FeatureHooks.i(
            "Welfare recovery request=${request.state.id} page=${request.page.id} " +
                "generation=${request.state.generation} callbackId=${request.state.callbackId} event=$event " +
                request.state.summary(),
        )
    }

    override fun close() {
        handler.post {
            pages.toList().forEach(::removePage)
            application?.unregisterActivityLifecycleCallbacks(lifecycle)
            application = null
            handler.removeCallbacksAndMessages(null)
        }
    }
}

private const val CLAIM_TIMEOUT_MS = 45_000L
private const val CALLBACK_SETTLE_MS = 350L
private const val RECEIPT_TIMEOUT_MS = 3_000L
