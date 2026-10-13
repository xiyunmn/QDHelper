package com.xiyunmn.qdhelper.runtime

import android.app.Application
import android.content.Context
import com.xiyunmn.qdhelper.feature.diagnostics.logging.FileLogSink
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.hook.runtime.HookRuntime
import com.xiyunmn.qdhelper.config.SettingsRuntime

object HostApplicationInitializer {
    private var logSettingsObserver: AutoCloseable? = null

    fun initialize(context: Context, processName: String, source: String) {
        val appContext = context.applicationContext ?: context
        SettingsRuntime.initialize(appContext)
        com.xiyunmn.qdhelper.settings.ui.SettingsUiRuntime.captureProcessSettings(appContext)
        FileLogSink.configure(appContext, SettingsRuntime.isFileLogEnabled(appContext))
        HookRuntime.configureFileLogSink(FileLogSink)
        if (logSettingsObserver == null) {
            logSettingsObserver = com.xiyunmn.qdhelper.config.ConfigRuntime.observeUserChanges(
                appContext,
                setOf(com.xiyunmn.qdhelper.config.SettingIds.KEY_DETAILED_LOG_ENABLED,
                    com.xiyunmn.qdhelper.config.SettingIds.KEY_MODULE_ENABLED),
            ) { FileLogSink.configure(appContext, SettingsRuntime.isFileLogEnabled(appContext)) }
        }
        if (HostSessionStore.update(appContext, processName)) {
            HookRuntime.i(
                "Host context initialized from $source: " +
                    "package=${appContext.packageName}, process=$processName",
            )
        }
    }

    fun markApplication(application: Application, source: String) {
        HostSessionStore.markApplication(application)
        initialize(application, HostSessionStore.processName, source)
    }

    fun clear() {
        logSettingsObserver?.close()
        logSettingsObserver = null
        HostSessionStore.contextOrNull()?.let { FileLogSink.configure(it, false) }
        SettingsRuntime.clearRuntimeContext()
        HookRuntime.configureFileLogSink(null)
        HostSessionStore.clear()
    }
}
