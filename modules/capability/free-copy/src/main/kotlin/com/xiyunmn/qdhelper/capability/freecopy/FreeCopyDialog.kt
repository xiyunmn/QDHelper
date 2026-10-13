package com.xiyunmn.qdhelper.capability.freecopy

import com.xiyunmn.qdhelper.shared.ui.registerBackHandler

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.text.Spanned
import android.text.SpannedString
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.xiyunmn.qdhelper.shared.compose.ModuleComposeContext
import com.xiyunmn.qdhelper.shared.compose.ModuleCompositionOwner
import com.xiyunmn.qdhelper.shared.compose.ModuleFloatingWindow
import com.xiyunmn.qdhelper.shared.compose.moduleWindowBrightness
import java.lang.ref.WeakReference

/** The composition root owns runtime subscriptions; this capability has no Feature dependency. */
interface FreeCopySession : AutoCloseable {
    val activity: Activity
    fun resume()
    fun pause()
}

object FreeCopyDialog {
    private var register: ((FreeCopySession) -> AutoCloseable)? = null
    private var reportError: (Throwable) -> Unit = {}
    private var current = WeakReference<CopyDialogSession>(null)

    fun configure(registerSession: (FreeCopySession) -> AutoCloseable, onError: (Throwable) -> Unit) {
        register = registerSession
        reportError = onError
    }

    /** Captures the source brightness/content before dismissing an optional native menu. */
    fun show(
        source: View,
        content: CharSequence,
        title: String = "评论内容",
        beforeShow: () -> Unit = {},
    ): FreeCopySession? {
        val activity = source.context.copyActivity() ?: return null
        if (activity.isFinishing || activity.isDestroyed) return null
        var session: CopyDialogSession? = null
        return try {
            val brightness = copyBrightness(source, activity)
            val snapshot = if (content is Spanned) SpannedString(content) else content.toString()
            current.get()?.close()
            session = CopyDialogSession(activity, snapshot, title, brightness, reportError)
            current = WeakReference(session)
            session.registration = checkNotNull(register) { "Free copy runtime is not configured" }(session)
            beforeShow()
            session.show()
            session
        } catch (error: Throwable) {
            session?.close()
            reportError(error)
            Toast.makeText(activity, "无法打开复制窗口，请重试", Toast.LENGTH_SHORT).show()
            null
        }
    }

    internal fun released(session: CopyDialogSession) {
        if (current.get() === session) current.clear()
    }
}

internal fun Context.copyActivity(): Activity? {
    var candidate: Context = this
    while (candidate is ContextWrapper) {
        if (candidate is Activity) return candidate
        val base = candidate.baseContext
        if (base === candidate) break
        candidate = base
    }
    return candidate as? Activity
}

internal fun copyBrightness(source: View, activity: Activity): Float = moduleWindowBrightness(source, activity)

internal class CopyDialogSession(
    override val activity: Activity,
    private val content: CharSequence,
    private val title: String,
    private val brightness: Float,
    private val reportError: (Throwable) -> Unit,
) : FreeCopySession {
    private val context = ModuleComposeContext.create(activity)
    private val owner = ModuleCompositionOwner()
    internal val view = ComposeView(context)
    internal val dialog = ModuleFloatingWindow(context, brightness)
    internal var registration: AutoCloseable? = null
    private var disposed = false
    internal var active by mutableStateOf(false)
        private set
    internal var closing by mutableStateOf(false)
        private set
    private var back: AutoCloseable? = null
    private var clearSelection: (() -> Unit)? = null

    fun show() {
        check(!disposed) { "Copy session already closed" }
        owner.attach(view)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(view)
        owner.attach(dialog.window!!.decorView)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnDismissListener { dispose() }
        dialog.onDismissStarted = { clearSelection?.invoke(); closing = true }
        dialog.setOnKeyListener { _, code, event ->
            if (code != KeyEvent.KEYCODE_BACK) false else {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) requestDismiss()
                true
            }
        }
        back = dialog.registerBackHandler(onBack = ::requestDismiss)
        view.setContent {
            CopyDialogContent(
                activity, content, title, active,
                dismiss = ::requestDismiss, copyAll = ::copyAll,
                installSelectionClear = { clearSelection = it },
            )
        }
        dialog.show()
        resume()
    }

    private fun requestDismiss() {
        if (disposed || closing) return
        clearSelection?.invoke()
        if (!active) close() else { closing = true; dialog.dismiss() }
    }

    private fun copyAll() {
        runCatching {
            val clipboard = checkNotNull(activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
            clipboard.setPrimaryClip(ClipData.newPlainText(title, content.toString()))
        }.onSuccess {
            Toast.makeText(activity, "已复制全文", Toast.LENGTH_SHORT).show()
            requestDismiss()
        }.onFailure {
            reportError(it)
            Toast.makeText(activity, "复制失败", Toast.LENGTH_SHORT).show()
        }
    }

    override fun resume() {
        if (disposed) return
        active = true
        owner.resume()
    }

    override fun pause() {
        if (disposed) return
        active = false
        clearSelection?.invoke()
        if (closing) close() else owner.pause()
    }

    override fun close() {
        dialog.dismissImmediately()
        dispose()
    }

    private fun dispose() {
        if (disposed) return
        disposed = true
        active = false
        clearSelection?.invoke()
        clearSelection = null
        dialog.onDismissStarted = null
        back?.close()
        back = null
        registration?.close()
        registration = null
        owner.close()
        view.disposeComposition()
        FreeCopyDialog.released(this)
    }
}
