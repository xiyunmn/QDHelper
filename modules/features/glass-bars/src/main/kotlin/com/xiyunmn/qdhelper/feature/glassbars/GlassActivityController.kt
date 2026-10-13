package com.xiyunmn.qdhelper.feature.glassbars

import android.app.Activity
import android.view.View
import android.view.ViewTreeObserver
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks

internal class GlassActivityController(
    private val activity: Activity,
    private val adapter: GlassBarAdapter,
    private val flutter: FlutterGlassBridge,
) : AutoCloseable {
    private val root = activity.window.decorView
    private var configuration = GlassBarsConfiguration()
    private var focused = activity.hasWindowFocus()
    private var session: GlassBarSession? = null
    private var suspended = false
    private var closed = false
    private var attempts = 0
    private var lastReason: String? = null
    private var observer: ViewTreeObserver? = null
    private val suspendAction = Runnable {
        session?.close()
        session = null
    }
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        if (!closed && !suspended && adapter.kind in configuration.enabled && (session == null || session?.closed == true) && attempts < 16) {
            bind()
        }
    }

    init {
        observer = root.viewTreeObserver.also { it.addOnGlobalLayoutListener(layoutListener) }
    }

    fun apply(configuration: GlassBarsConfiguration) {
        if (closed) return
        val wasEnabled = adapter.kind in this.configuration.enabled
        this.configuration = configuration
        if (adapter.kind !in configuration.enabled) {
            root.removeCallbacks(suspendAction)
            session?.close()
            session = null
            suspended = false
            return
        }
        if (!wasEnabled) {
            suspended = false
            attempts = 0
        }
        if (suspended) return
        if (session == null || session?.closed == true) bind()
        else session?.update(configuration.effects, configuration.dimensionsFor(adapter.kind))
    }

    fun focus(value: Boolean) {
        focused = value
        session?.setActive(value)
        if (value && !suspended && adapter.kind in configuration.enabled && (session == null || session?.closed == true)) {
            attempts = 0
            bind()
        }
    }

    fun touch(action: Int, x: Float, y: Float, eventTime: Long) = session?.observeTouch(action, x, y, eventTime)

    fun engineAttached(view: View) = session?.engineAttached(view)

    fun retryBinding() {
        suspended = false
        attempts = 0
        root.removeCallbacks(suspendAction)
        apply(configuration)
    }

    private fun bind() {
        if (closed || activity.isDestroyed || activity.isFinishing) return
        attempts++
        when (val result = adapter.bind(root)) {
            is GlassBindingResult.Unavailable -> report(result.reason)
            is GlassBindingResult.Bound -> {
                try {
                    val next = GlassBarSession(result.binding, flutter, ::suspend)
                    session = next
                    next.attach(configuration.effects, focused, configuration.dimensionsFor(adapter.kind))
                    if (!suspended) FeatureHooks.i("Glass bar bound: kind=${adapter.kind}")
                } catch (error: Exception) {
                    session?.close()
                    session = null
                    suspend("${adapter.kind.name.lowercase()}.bind-${error.javaClass.simpleName}")
                }
            }
        }
    }

    private fun suspend(reason: String) {
        if (closed || suspended) return
        suspended = true
        report(reason)
        // A whole main binding fails closed; individual tabs never keep the old content layout.
        root.post(suspendAction)
    }

    private fun report(reason: String) {
        if (lastReason == reason) return
        lastReason = reason
        FeatureHooks.w("Glass bar unavailable: kind=${adapter.kind}, reason=$reason")
    }

    override fun close() {
        if (closed) return
        closed = true
        root.removeCallbacks(suspendAction)
        observer?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(layoutListener)
        observer = null
        session?.close()
        session = null
    }
}
