package com.xiyunmn.qdhelper.foundation.logging

import android.util.Log

/** Small platform sink for entry-point components that cannot depend on FeatureHooks. */
object AndroidLog {
    fun info(tag: String, message: String) {
        Log.i(tag, SafeLog.sanitize(message))
    }

    fun warn(tag: String, message: String, throwable: Throwable? = null) {
        val safeMessage = SafeLog.sanitize(message)
        if (throwable == null) Log.w(tag, safeMessage) else Log.w(tag, safeMessage, throwable)
    }
}
