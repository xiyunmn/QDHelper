package com.xiyunmn.qdhelper.feature.readerfont.ui

import android.view.View
import android.view.ViewTreeObserver
import java.lang.ref.WeakReference

/** Keeps native font injection posted, and scopes the covered menu to that panel's lifetime. */
internal class ReaderFontPanelOverlay(panel: View, onShown: (View) -> View?) : AutoCloseable {
    private val panelRef = WeakReference(panel)
    private var covered: WeakReference<View>? = null
    private var observer: WeakReference<ViewTreeObserver>? = null
    private var ownsVisibility = false
    private var closed = false
    private val retry = Runnable { hideCoveredMenu() }
    private val layout = ViewTreeObserver.OnGlobalLayoutListener {
        // Native menu reopening uses setVisibility(GONE), without calling panel.dismiss().
        if (!isPanelShown()) close()
    }
    private val attachment = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) = close()
    }
    private val show = Runnable {
        if (!closed) {
            val view = panelRef.get()
            if (view == null || !isPanelShown()) close()
            else {
                covered = onShown(view)?.let(::WeakReference)
                observer = WeakReference(view.viewTreeObserver)
                view.viewTreeObserver.addOnGlobalLayoutListener(layout)
                hideCoveredMenu()
                if (!closed) view.postDelayed(retry, 300L)
            }
        }
    }

    init {
        panel.addOnAttachStateChangeListener(attachment)
        panel.post(show)
    }

    private fun isPanelShown() = panelRef.get()?.let { it.isAttachedToWindow && it.isShown } == true

    private fun hideCoveredMenu() {
        if (closed) return
        if (!isPanelShown()) { close(); return }
        covered?.get()?.takeIf { it.visibility == View.VISIBLE }?.let {
            ownsVisibility = true
            it.visibility = View.INVISIBLE
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        panelRef.get()?.let {
            it.removeCallbacks(show)
            it.removeCallbacks(retry)
            it.removeOnAttachStateChangeListener(attachment)
        }
        observer?.get()?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(layout)
        if (ownsVisibility) covered?.get()?.takeIf { it.visibility == View.INVISIBLE }?.visibility = View.VISIBLE
        ownsVisibility = false
    }
}
