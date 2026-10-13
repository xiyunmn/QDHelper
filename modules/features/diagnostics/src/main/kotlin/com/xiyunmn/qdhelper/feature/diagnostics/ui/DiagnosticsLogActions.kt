package com.xiyunmn.qdhelper.feature.diagnostics.ui

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import com.xiyunmn.qdhelper.feature.api.runtime.ActivityCallbacks
import com.xiyunmn.qdhelper.feature.diagnostics.logging.FileLogSink
import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import com.xiyunmn.qdhelper.hook.activity.ActivityResultRegistry
import com.xiyunmn.qdhelper.hook.concurrency.ManagedExecutor
import com.xiyunmn.qdhelper.shared.io.OemFilePickerLauncher
import com.xiyunmn.qdhelper.shared.ui.showModuleConfirmDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object DiagnosticsLogActions {
    private const val REQUEST_EXPORT_LOGS = 0x514456
    private val worker = ManagedExecutor("feature.diagnostics.log-actions", "QDHelper-LogActions")

    fun export(activity: Activity) {
        val registration = ActivityCallbacks.registerResult(OwnerId("diagnostics"), REQUEST_EXPORT_LOGS) { host, code, data ->
            val uri = data?.data
            if (code != Activity.RESULT_OK || uri == null) {
                notify(host, "已取消导出")
            } else {
                val context = host.applicationContext
                if (!worker.execute {
                    val message = runCatching {
                        val bytes = FileLogSink.archive(context)
                        val stream = context.contentResolver.openOutputStream(uri, "wt") ?: error("无法打开保存位置")
                        stream.use { it.write(bytes) }
                        "日志已导出"
                    }.getOrElse { "导出失败：${it.message ?: it.javaClass.simpleName}" }
                    notify(host, message)
                }) notify(host, "导出未启动，请重试")
            }
            true
        }
        if (registration is ActivityResultRegistry.RegistrationResult.RequestCodeConflict) {
            notify(activity, "日志导出暂不可用")
            return
        }
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, "QDHelper-logs-$timestamp.zip")
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        if (!OemFilePickerLauncher.startActivityForResult(activity, intent, REQUEST_EXPORT_LOGS)) {
            notify(activity, "无法打开文件保存位置")
        }
    }

    fun clear(activity: Activity) {
        showModuleConfirmDialog(activity, "清理日志", "删除本机现有模块日志。详细日志开启时仍会继续记录新日志。", "清理") {
            val context = activity.applicationContext
            if (!worker.execute {
                val message = runCatching {
                    if (FileLogSink.clearLogs(context) == 0) "暂无日志可清理" else "日志已清理"
                }.getOrElse { "清理失败：${it.message ?: it.javaClass.simpleName}" }
                notify(activity, message)
            }) notify(activity, "清理未启动，请重试")
        }
    }

    private fun notify(activity: Activity, message: String) {
        activity.runOnUiThread { Toast.makeText(activity.applicationContext, message, Toast.LENGTH_LONG).show() }
    }
}
