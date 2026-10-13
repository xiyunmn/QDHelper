package com.xiyunmn.qdhelper.foundation.runtime

import android.app.Application
import android.content.Context
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/** Canonical process session state. It deliberately owns no settings or logging side effects. */
object HostSessionStore {
    private val initialized = AtomicBoolean(false)

    @Volatile
    private var contextRef: WeakReference<Context>? = null

    @Volatile
    var processName: String = ""
        private set

    @Volatile
    var applicationClassName: String = ""
        private set

    fun update(context: Context, processName: String): Boolean {
        contextRef = WeakReference(context.applicationContext ?: context)
        this.processName = processName
        return initialized.compareAndSet(false, true)
    }

    fun markApplication(application: Application) {
        applicationClassName = application.javaClass.name
        update(application, processName)
    }

    fun contextOrNull(): Context? = contextRef?.get()

    fun clear() {
        contextRef = null
        processName = ""
        applicationClassName = ""
        initialized.set(false)
    }
}
