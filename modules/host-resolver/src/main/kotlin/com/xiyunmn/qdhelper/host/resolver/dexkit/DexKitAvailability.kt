package com.xiyunmn.qdhelper.host.resolver.dexkit

import android.content.Context
import android.os.Build
import android.os.Process
import com.xiyunmn.qdhelper.foundation.android.AndroidApiSupport
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import com.xiyunmn.qdhelper.host.resolver.environment.ResolverEnvironment
import java.io.File

internal object DexKitAvailability {
    fun unavailableReason(context: Context?, processName: String): String? {
        if (context == null) {
            return "context_missing"
        }
        if (!HostSymbols.isMainProcess(processName) && !HostSymbols.isMiniProcess(processName)) {
            return "unsupported_process"
        }
        AndroidApiSupport.unsupportedReason()?.let { return it }
        if (!ResolverEnvironment.isDexKitEnabled(context)) {
            return "disabled_by_setting"
        }
        NativeAbiSupport.unsupportedReason()?.let { return it }
        val sourcePath = runCatching {
            context.packageManager.getApplicationInfo(HostSymbols.PACKAGE, 0).sourceDir
        }.getOrNull()
        if (sourcePath.isNullOrBlank() || !File(sourcePath).isFile) {
            return "host_apk_missing:${environmentSummary(context)}"
        }
        DexKitEngine.nativeUnavailableReason(context)?.let { return it }
        return null
    }

    fun environmentSummary(context: Context): String {
        val appInfo = runCatching {
            context.packageManager.getApplicationInfo(HostSymbols.PACKAGE, 0)
        }.getOrNull()
        val apkCount = listOfNotNull(appInfo?.sourceDir).size + appInfo?.splitSourceDirs.orEmpty().size
        return "abi=${Build.SUPPORTED_ABIS.joinToString(",")};process64=${Process.is64Bit()};" +
            "apkParts=$apkCount;native=${DexKitEngine.nativeState()}"
    }
}
