package com.xiyunmn.qdhelper.host.resolver.environment

import android.content.Context
import com.xiyunmn.qdhelper.config.ConfigRuntime
import com.xiyunmn.qdhelper.config.SettingIds
import com.xiyunmn.qdhelper.host.resolver.prewarm.TargetPrewarmPlanner
import com.xiyunmn.qdhelper.host.resolver.reflection.ReflectionAccess
import com.xiyunmn.qdhelper.host.resolver.runtime.ResolverInstallRetryEvents
import com.xiyunmn.qdhelper.host.resolver.runtime.ResolverSessionRegistry

object ResolverEnvironment {
    @Volatile
    private var infoLogger: (String) -> Unit = {}

    @Volatile
    private var warnLogger: (String) -> Unit = {}

    fun configure(info: (String) -> Unit, warn: (String) -> Unit) {
        infoLogger = info
        warnLogger = warn
    }

    fun info(message: String) = infoLogger(message)

    fun warn(message: String) = warnLogger(message)

    fun isDexKitEnabled(context: Context?): Boolean = enabled(context, SettingIds.KEY_DEBUG_DEXKIT_ENABLED)

    fun isForceRescanEnabled(context: Context?): Boolean = enabled(context, SettingIds.KEY_DEBUG_DEXKIT_FORCE_RESCAN)

    fun isCandidateLogEnabled(context: Context?): Boolean = enabled(context, SettingIds.KEY_DEBUG_DEXKIT_LOG_CANDIDATES)

    fun close() {
        ResolverInstallRetryEvents.clear()
        TargetPrewarmPlanner.close()
        ResolverSessionRegistry.close()
        ReflectionAccess.clear()
        infoLogger = {}
        warnLogger = {}
    }

    private fun enabled(context: Context?, key: String): Boolean {
        context ?: return false
        ConfigRuntime.initialize(context)
        return ConfigRuntime.readBoolean(SettingIds.KEY_MODULE_ENABLED) && ConfigRuntime.readBoolean(key)
    }
}
