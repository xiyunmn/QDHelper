package com.xiyunmn.qdhelper.hook.framework

import io.github.libxposed.api.XposedModule
import java.util.Locale

/** Names come from the injecting framework, not installed manager applications. */
object HookFrameworkInfo {
    fun name(module: XposedModule): String =
        normalizeName(runCatching { module.frameworkName }.getOrNull())

    fun normalizeName(value: String?): String {
        val name = value.orEmpty().trim().replace(Regex("[\\p{Cntrl}\\s]+"), " ")
        return when (name.lowercase(Locale.ROOT).replace(Regex("[ _-]"), "")) {
            "lsposed" -> "LSPosed"
            "relsposed" -> "ReLSPosed"
            "vector" -> "Vector"
            "lspatch" -> "LSPatch"
            "npatch" -> "NPatch"
            "opatch" -> "OPatch"
            "edxposed" -> "EdXposed"
            "xposed" -> "Xposed"
            "", "unknown", "null" -> "未知"
            else -> name
        }
    }
}
