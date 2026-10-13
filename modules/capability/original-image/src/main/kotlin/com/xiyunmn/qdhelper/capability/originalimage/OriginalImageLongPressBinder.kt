package com.xiyunmn.qdhelper.capability.originalimage

import android.content.Context
import android.view.View
import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import com.xiyunmn.qdhelper.hook.view.LongPressRegistration
import com.xiyunmn.qdhelper.hook.view.LongPressRegistrationResult
import com.xiyunmn.qdhelper.hook.view.LongPressResult
import com.xiyunmn.qdhelper.hook.view.ViewLongPressRouter
import java.util.Collections
import java.util.WeakHashMap

object OriginalImageLongPressBinder {
    private val registrations = Collections.synchronizedMap(WeakHashMap<View, AutoCloseable>())

    @Volatile
    private var router: ViewLongPressRouter? = null

    @Volatile
    private var errorLogger: (String, Throwable) -> Unit = { _, _ -> }

    fun configure(router: ViewLongPressRouter, errorLogger: (String, Throwable) -> Unit) {
        this.router = router
        this.errorLogger = errorLogger
    }

    fun register(
        view: View,
        isEnabled: (Context) -> Boolean,
        open: (Context) -> Unit,
    ) {
        unregister(view)
        val activeRouter = router ?: return
        val result = activeRouter.register(
            view,
            LongPressRegistration(OwnerId("original-image"), "viewer", priority = 100),
        ) { target, _ ->
            val targetView = target as View
            val enabled = runCatching { isEnabled(targetView.context) }.getOrElse { throwable ->
                errorLogger("Original image setting check failed", throwable)
                unregister(targetView)
                return@register LongPressResult.Pass
            }
            if (!enabled) {
                unregister(targetView)
                return@register LongPressResult.Pass
            }
            runCatching { open(targetView.context) }.fold(
                onSuccess = { LongPressResult.Return(true) },
                onFailure = { throwable ->
                    errorLogger("Original image viewer launch failed", throwable)
                    LongPressResult.Pass
                },
            )
        }
        if (result is LongPressRegistrationResult.Registered) registrations[view] = result.registration
    }

    fun unregister(view: View) {
        registrations.remove(view)?.close()
    }
}
