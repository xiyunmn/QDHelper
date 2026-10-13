package com.xiyunmn.qdhelper.settings.ui.compose.state

import android.os.Handler
import android.os.Looper

/** Workers retain only this detachable mailbox, never a disposed composition or host Activity. */
internal class SettingsResultSink<T> : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    @Volatile var consumer: ((T) -> Unit)? = null
    fun deliver(value: T) { handler.post { consumer?.invoke(value) } }
    override fun close() { consumer = null; handler.removeCallbacksAndMessages(null) }
}
