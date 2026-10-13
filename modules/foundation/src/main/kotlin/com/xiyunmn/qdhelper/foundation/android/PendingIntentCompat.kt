package com.xiyunmn.qdhelper.foundation.android

import android.app.ActivityOptions
import android.os.Build
import android.os.Bundle

/** API-level guarded PendingIntent options shared by both restart entry points. */
object PendingIntentCompat {
    fun creatorOptions(): Bundle = ActivityOptions.makeBasic().apply {
        if (Build.VERSION.SDK_INT >= 35) {
            setPendingIntentCreatorBackgroundActivityStartMode(backgroundActivityStartMode())
        }
    }.toBundle()

    fun senderOptions(): Bundle = ActivityOptions.makeBasic().apply {
        if (Build.VERSION.SDK_INT >= 34) {
            setPendingIntentBackgroundActivityStartMode(backgroundActivityStartMode())
        }
    }.toBundle()

    @Suppress("DEPRECATION")
    @android.annotation.TargetApi(34)
    private fun backgroundActivityStartMode(): Int = if (Build.VERSION.SDK_INT >= 36) {
        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
    } else {
        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
    }
}
