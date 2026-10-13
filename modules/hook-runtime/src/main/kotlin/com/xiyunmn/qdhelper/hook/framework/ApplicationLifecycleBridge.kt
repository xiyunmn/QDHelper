package com.xiyunmn.qdhelper.hook.framework

import android.app.Application

object ApplicationLifecycleBridge {
    fun currentApplicationOrNull(onFailure: (Throwable) -> Unit = {}): Application? {
        return runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            val currentApplication = activityThread.getDeclaredMethod("currentApplication").apply {
                isAccessible = true
            }.invoke(null) as? Application
            currentApplication ?: run {
                val currentThread = activityThread.getDeclaredMethod("currentActivityThread").apply {
                    isAccessible = true
                }.invoke(null) ?: return@run null
                activityThread.getDeclaredField("mInitialApplication").apply {
                    isAccessible = true
                }.get(currentThread) as? Application
            }
        }.getOrElse { throwable ->
            onFailure(throwable)
            null
        }
    }
}
