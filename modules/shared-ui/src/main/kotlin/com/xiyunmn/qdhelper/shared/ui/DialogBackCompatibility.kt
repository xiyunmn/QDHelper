package com.xiyunmn.qdhelper.shared.ui

import android.annotation.TargetApi
import android.app.Dialog
import android.os.Build
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher

/** Older dialogs keep their existing KEYCODE_BACK listener; newer APIs stay behind version checks. */
fun Dialog.registerBackHandler(
    onBack: () -> Unit,
    onStarted: () -> Unit = {},
    onProgress: ((Float) -> Unit)? = null,
    onCancelled: () -> Unit = {},
): AutoCloseable = when {
    Build.VERSION.SDK_INT >= 34 && onProgress != null ->
        DialogBackApi34.register(this, onBack, onStarted, onProgress, onCancelled)
    Build.VERSION.SDK_INT >= 33 -> DialogBackApi33.register(this, onBack)
    else -> AutoCloseable {}
}

@TargetApi(33)
private object DialogBackApi33 {
    fun register(dialog: Dialog, onBack: () -> Unit): AutoCloseable {
        val callback = OnBackInvokedCallback(onBack)
        dialog.onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
        return AutoCloseable { dialog.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback) }
    }
}

@TargetApi(34)
private object DialogBackApi34 {
    fun register(dialog: Dialog, onBack: () -> Unit, onStarted: () -> Unit, onProgress: (Float) -> Unit,
                 onCancelled: () -> Unit): AutoCloseable {
        val callback = object : OnBackAnimationCallback {
            override fun onBackStarted(backEvent: BackEvent) = onStarted()
            override fun onBackProgressed(backEvent: BackEvent) = onProgress(backEvent.progress)
            override fun onBackCancelled() = onCancelled()
            override fun onBackInvoked() = onBack()
        }
        dialog.onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
        return AutoCloseable { dialog.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback) }
    }
}
