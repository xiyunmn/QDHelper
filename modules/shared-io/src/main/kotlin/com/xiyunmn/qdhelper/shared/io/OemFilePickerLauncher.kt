package com.xiyunmn.qdhelper.shared.io

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import java.util.Locale

object OemFilePickerLauncher {
    fun startActivityForResult(activity: Activity, intent: Intent, requestCode: Int): Boolean {
        preferredComponents(activity, intent).forEach { component ->
            val launched = runCatching {
                activity.startActivityForResult(Intent(intent).setComponent(component), requestCode)
            }.isSuccess
            if (launched) return true
        }
        return runCatching {
            activity.startActivityForResult(Intent(intent).setComponent(null).setPackage(null), requestCode)
        }.isSuccess
    }

    private fun preferredComponents(activity: Activity, intent: Intent): List<ComponentName> {
        val handlers = queryHandlers(activity.packageManager, intent).mapNotNull { resolveInfo ->
            val activityInfo = resolveInfo.activityInfo ?: return@mapNotNull null
            val applicationInfo = activityInfo.applicationInfo ?: return@mapNotNull null
            FilePickerHandler(
                packageName = activityInfo.packageName,
                className = activityInfo.name,
                appLabel = runCatching { resolveInfo.loadLabel(activity.packageManager).toString() }
                    .getOrDefault(""),
                systemApp = applicationInfo.flags and SYSTEM_APP_FLAGS != 0,
            )
        }
        return OemFilePickerPolicy.preferred(handlers).map { handler ->
            ComponentName(handler.packageName, handler.className)
        }
    }

    private fun queryHandlers(packageManager: PackageManager, intent: Intent) =
        packageManager.queryIntentActivities(
            intent,
            PackageManager.MATCH_DEFAULT_ONLY,
        )

    private const val SYSTEM_APP_FLAGS =
        ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
}

internal data class FilePickerHandler(
    val packageName: String,
    val className: String,
    val appLabel: String,
    val systemApp: Boolean,
)

internal object OemFilePickerPolicy {
    fun preferred(handlers: List<FilePickerHandler>): List<FilePickerHandler> = handlers
        .filter { handler ->
            !handler.packageName.lowercase(Locale.ROOT).contains("documentsui") &&
                handler.looksLikeFileManager()
        }
        .sortedByDescending(FilePickerHandler::systemApp)

    private fun FilePickerHandler.looksLikeFileManager(): Boolean {
        val componentIdentity = "$packageName $className".lowercase(Locale.ROOT)
        val normalizedLabel = appLabel.lowercase(Locale.ROOT)
        return COMPONENT_TOKENS.any(componentIdentity::contains) ||
            LABEL_TOKENS.any(normalizedLabel::contains)
    }

    private val COMPONENT_TOKENS = listOf(
        "filemanager",
        "fileexplorer",
        "filebrowser",
        "filepicker",
        "folderpicker",
        "myfiles",
        "hidisk",
        ".files",
    )
    private val LABEL_TOKENS = listOf(
        "file manager",
        "files",
        "文件",
        "檔案",
        "ファイル",
    )
}
