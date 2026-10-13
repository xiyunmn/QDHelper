package com.xiyunmn.qdhelper.hook.logging

import android.util.Log
import io.github.libxposed.api.XposedModule

object LibXposedRuntimeLogger {
    private const val TAG = "QDHelper"

    @Volatile
    private var module: XposedModule? = null

    @Volatile
    private var frameworkLogSuppressed = false

    fun attach(value: XposedModule) {
        module = value
    }

    fun detach(value: XposedModule) {
        if (module === value) module = null
    }

    fun info(message: String) {
        Log.i(TAG, message)
        if (!frameworkLogSuppressed) module?.log(Log.INFO, TAG, message)
    }

    fun warn(message: String) {
        Log.w(TAG, message)
        if (!frameworkLogSuppressed) module?.log(Log.WARN, TAG, message)
    }

    fun error(message: String, throwable: Throwable?) {
        if (throwable == null) {
            Log.e(TAG, message)
            if (!frameworkLogSuppressed) module?.log(Log.ERROR, TAG, message)
        } else {
            Log.e(TAG, message, throwable)
            if (!frameworkLogSuppressed) module?.log(Log.ERROR, TAG, message, throwable)
        }
    }

    fun <T> withFrameworkLogSuppressed(block: () -> T): T {
        val previous = frameworkLogSuppressed
        frameworkLogSuppressed = true
        return try {
            block()
        } finally {
            frameworkLogSuppressed = previous
        }
    }
}
