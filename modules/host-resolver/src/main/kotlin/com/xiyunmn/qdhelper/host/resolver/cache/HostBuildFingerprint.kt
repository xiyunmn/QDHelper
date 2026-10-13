package com.xiyunmn.qdhelper.host.resolver.cache

import android.content.Context
import android.content.pm.PackageManager
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import java.io.File
import java.security.MessageDigest

data class HostBuildFingerprint(
    val versionName: String,
    val versionCode: Long,
    val lastUpdateTime: Long,
    val apkParts: List<ApkPart>,
    val processName: String,
    val resolverSchema: Int,
    val dexKitVersion: String,
    val catalogSha256: String,
) {
    data class ApkPart(val path: String, val length: Long, val lastModified: Long)

    internal fun cacheSha256(): String = copy(catalogSha256 = "").sha256()

    fun sha256(): String {
        val canonical = buildString {
            appendLine(versionName)
            appendLine(versionCode)
            appendLine(lastUpdateTime)
            apkParts.sortedBy { it.path }.forEach { part ->
                appendLine("${part.path}\u0000${part.length}\u0000${part.lastModified}")
            }
            appendLine(processName)
            appendLine(resolverSchema)
            appendLine(dexKitVersion)
            append(catalogSha256)
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    companion object {
        fun from(
            context: Context,
            processName: String,
            resolverSchema: Int,
            dexKitVersion: String,
            catalogSha256: String,
        ): HostBuildFingerprint {
            val packageInfo = context.packageManager.getPackageInfo(
                HostSymbols.PACKAGE,
                0,
            )
            val appInfo = context.packageManager.getApplicationInfo(
                HostSymbols.PACKAGE,
                0,
            )
            val versionCode = packageInfo.longVersionCode
            val paths = listOfNotNull(appInfo.sourceDir) + appInfo.splitSourceDirs.orEmpty()
            return HostBuildFingerprint(
                versionName = packageInfo.versionName.orEmpty(),
                versionCode = versionCode,
                lastUpdateTime = packageInfo.lastUpdateTime,
                apkParts = paths.distinct().sorted().map { path ->
                    val file = File(path)
                    ApkPart(path, file.length(), file.lastModified())
                },
                processName = processName,
                resolverSchema = resolverSchema,
                dexKitVersion = dexKitVersion,
                catalogSha256 = catalogSha256,
            )
        }
    }
}
