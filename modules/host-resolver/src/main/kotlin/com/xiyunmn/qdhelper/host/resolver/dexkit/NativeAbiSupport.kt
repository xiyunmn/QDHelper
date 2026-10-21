package com.xiyunmn.qdhelper.host.resolver.dexkit

import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File

/**
 * Runtime preflight for the packaged ARM64 DexKit library.
 *
 * The host process can still be a 32-bit process on a 64-bit device.
 * Checking both values before touching DexKit keeps that
 * case a normal, diagnosable capability miss instead of an UnsatisfiedLinkError/ART crash.
 */
internal object NativeAbiSupport {
    private val supportedModuleAbis = setOf("arm64-v8a")
    private const val MODULE_PACKAGE = "io.github.xiyunmn.qdhelper"
    const val UNSUPPORTED_ABI = "unsupported_abi"
    const val UNSUPPORTED_PROCESS_ABI = "unsupported_process_abi"
    const val NATIVE_MISSING = "dexkit_native_missing"
    const val NATIVE_LOAD_FAILED = "native_load_failed"

    fun unsupportedReason(): String? =
        unsupportedReason(Build.SUPPORTED_ABIS.asList(), Process.is64Bit())

    fun unsupportedReason(supportedAbis: Iterable<String>, processIs64Bit: Boolean): String? {
        if (supportedAbis.none { it in supportedModuleAbis }) return UNSUPPORTED_ABI
        if (!processIs64Bit) return UNSUPPORTED_PROCESS_ABI
        return null
    }

    /** Returns the module's packaged DexKit library when it can be located without loading it. */
    fun nativeLibraryFile(context: Context?): File? {
        context ?: return null
        val appInfo = runCatching {
            context.packageManager.getApplicationInfo(MODULE_PACKAGE, 0)
        }.getOrNull() ?: return null
        return File(appInfo.nativeLibraryDir, System.mapLibraryName("dexkit"))
            .takeIf(File::isFile)
    }
}
