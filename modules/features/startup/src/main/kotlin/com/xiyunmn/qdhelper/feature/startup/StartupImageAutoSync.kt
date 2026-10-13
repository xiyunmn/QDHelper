package com.xiyunmn.qdhelper.feature.startup

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import com.xiyunmn.qdhelper.config.ConfigRuntime
import com.xiyunmn.qdhelper.config.SettingIds
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureResources
import com.xiyunmn.qdhelper.hook.concurrency.ManagedExecutor
import java.util.concurrent.Future

/** Debounced background synchronization for startup-image setting changes. */
object StartupImageAutoSync {
    private val synchronizer by lazy { StartupImageSynchronizer() }

    fun schedule(context: Context?) = synchronizer.schedule(context)

    fun syncNow(context: Context?) = synchronizer.schedule(context, immediate = true)
}

internal class StartupImageSynchronizer(
    private val activeTarget: (Context) -> StartupImageStore.SplashTarget? = StartupImageStore::activeSplashTarget,
    private val sync: (Context, StartupImageStore.SplashTarget, () -> Boolean) -> SyncResult = StartupImageStore::sync,
    private val notify: (Context, String) -> Unit = { context, message ->
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    },
) : AutoCloseable {
    private val lock = Any()
    private val handler = Handler(Looper.getMainLooper())
    private val callbackToken = Any()
    private val worker = ManagedExecutor(
        ownerKey = "feature.startup.image-auto-sync@${System.identityHashCode(this)}",
        threadName = "QDHelper-StartupAutoSync",
        onFailure = { FeatureHooks.w("Startup image sync failed: ${it.javaClass.name}") },
    )
    private var closed = false
    private var generation = 0L
    private var current: Request? = null
    private var settings: AutoCloseable? = null
    private var cleanup: AutoCloseable? = null

    init {
        cleanup = FeatureResources.register("startup.image-sync@${System.identityHashCode(this)}", ::close)
    }

    private inner class Request(val context: Context, val target: StartupImageStore.SplashTarget, val generation: Long) {
        val start = Runnable { launch(this) }
        var started = false
        var finished = false
        var future: Future<*>? = null
    }

    /** Uses applicationContext so pending work never retains the settings Activity. */
    fun schedule(context: Context?, immediate: Boolean = false) = schedule(context, immediate, invalidate = false)

    private fun schedule(context: Context?, immediate: Boolean, invalidate: Boolean) {
        val ctx = context?.applicationContext ?: context ?: return
        synchronized(lock) {
            if (closed) return
            if (settings == null) {
                settings = ConfigRuntime.observeUserChanges(ctx, IMAGE_SETTINGS) {
                    schedule(ctx, immediate = false, invalidate = true)
                }
            }
            val target = activeTarget(ctx)
            val previous = current
            if (!invalidate && previous != null && !previous.finished && previous.target.sameImage(target)) {
                if (immediate && !previous.started) {
                    handler.removeCallbacks(previous.start)
                    launch(previous)
                }
                return
            }
            generation++
            current = null
            previous?.let { handler.removeCallbacks(it.start); it.future?.cancel(true) }
            if (target == null) {
                if (immediate) postMessage(ctx, generation, null, "请先选择并启用启动图")
                return
            }
            val request = Request(ctx, target, generation)
            current = request
            if (immediate) {
                postMessage(ctx, generation, target, "正在同步启动图")
                launch(request)
            } else if (!handler.postDelayed(request.start, callbackToken, DEBOUNCE_MS)) {
                request.finished = true
            }
        }
    }

    private fun launch(request: Request) {
        synchronized(lock) {
            if (!isCurrent(request) || request.started) return
            request.started = true
        }
        val future = worker.submit {
            try {
                if (!isCurrent(request)) return@submit
                val result = runCatching { sync(request.context, request.target) { isCurrent(request) } }
                    .getOrElse {
                        if (isCurrent(request)) FeatureHooks.w("Startup image sync failed: ${it.javaClass.name}")
                        SyncResult(false, "启动图同步失败")
                    }
                if (!result.cancelled && isCurrent(request)) {
                    postMessage(request.context, request.generation, request.target, result.message)
                }
            } finally {
                synchronized(lock) { request.finished = true }
            }
        }
        synchronized(lock) {
            request.future = future
            if (!isCurrent(request)) future.cancel(true)
            if (future.isCancelled) request.finished = true
        }
    }

    private fun isCurrent(request: Request): Boolean = synchronized(lock) {
        !closed && current === request && generation == request.generation && request.target.sameImage(activeTarget(request.context))
    }

    private fun postMessage(context: Context, token: Long, target: StartupImageStore.SplashTarget?, message: String) {
        handler.postAtTime({
            synchronized(lock) {
                if (!closed && generation == token && (target == null || target.sameImage(activeTarget(context)))) {
                    runCatching { notify(context, message) }
                }
            }
        }, callbackToken, SystemClock.uptimeMillis())
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            generation++
            current?.future?.cancel(true)
            current = null
            handler.removeCallbacksAndMessages(callbackToken)
            settings?.close()
            settings = null
            cleanup?.close()
            cleanup = null
        }
        worker.close()
    }

    private companion object {
        const val DEBOUNCE_MS = 900L
        // Logo changes do not change the cache image and must not cancel an in-flight write.
        val IMAGE_SETTINGS = setOf(
            SettingIds.KEY_MODULE_ENABLED,
            SettingIds.KEY_LOCAL_SPLASH_IMAGE_ENABLED,
            SettingIds.KEY_LOCAL_SPLASH_IMAGE_PATH,
            SettingIds.KEY_CUSTOM_SPLASH_IMAGE_ENABLED,
            SettingIds.KEY_CUSTOM_SPLASH_IMAGE_URL,
            SettingIds.KEY_OFFICIAL_SPLASH_IMAGE_ENABLED,
            SettingIds.KEY_OFFICIAL_SPLASH_IMAGE_URL,
            SettingIds.KEY_OFFICIAL_SPLASH_IMAGE_PAPER_ID,
            SettingIds.KEY_OFFICIAL_SPLASH_IMAGE_LOCAL_UNLOCK_ENABLED,
            SettingIds.KEY_OFFICIAL_SPLASH_IMAGE_LOCAL_URL,
            SettingIds.KEY_OFFICIAL_SPLASH_IMAGE_LOCAL_PAPER_ID,
        )
    }
}
