package com.xiyunmn.qdhelper.feature.maintenance.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.xiyunmn.qdhelper.config.BookShieldRuleTransfer
import com.xiyunmn.qdhelper.feature.api.runtime.ActivityCallbacks
import com.xiyunmn.qdhelper.feature.maintenance.ActionResult
import com.xiyunmn.qdhelper.feature.maintenance.MaintenanceActions
import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import com.xiyunmn.qdhelper.hook.activity.ActivityResultRegistry
import com.xiyunmn.qdhelper.hook.concurrency.ManagedExecutor
import com.xiyunmn.qdhelper.shared.io.OemFilePickerLauncher
import com.xiyunmn.qdhelper.shared.io.SizeLimitExceededException
import com.xiyunmn.qdhelper.shared.io.readBytesBounded
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object MaintenanceConfigFilePicker {
    private const val REQUEST_EXPORT_CONFIG = 0x514443
    private const val REQUEST_IMPORT_CONFIG = 0x514444
    private const val REQUEST_EXPORT_BOOK_SHIELD_RULES = 0x514454
    private const val REQUEST_IMPORT_BOOK_SHIELD_RULES = 0x514455
    private const val MAX_IMPORT_BYTES = 2L * 1024L * 1024L
    private val maxRuleImportBytes = BookShieldRuleTransfer.MAX_FILE_BYTES.toLong()
    private val yamlMimeTypes = arrayOf(
        "text/plain",
        "text/yaml",
        "text/x-yaml",
        "application/yaml",
        "application/x-yaml",
    )
    private val owner = OwnerId("maintenance")
    private val worker = ManagedExecutor(
        ownerKey = "feature.maintenance.config-file",
        threadName = "QDHelper-MaintenanceConfig",
    )

    @Volatile
    private var pendingImportRefresh: (() -> Unit)? = null

    @Volatile
    private var pendingRuleImportRefresh: (() -> Unit)? = null

    fun exportConfig(activity: Activity) {
        if (!registerExportHandler()) {
            showToast(activity, "配置导出暂不可用")
            return
        }
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, "QDHelper-config-$timestamp.zip")
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        launchPicker(activity, intent, REQUEST_EXPORT_CONFIG, "无法打开文件保存位置")
    }

    fun importConfig(activity: Activity, onImported: () -> Unit) {
        if (!registerImportHandler()) {
            showToast(activity, "配置导入暂不可用")
            return
        }
        pendingImportRefresh = onImported
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (!launchPicker(activity, intent, REQUEST_IMPORT_CONFIG, "无法打开配置文件选择器")) {
            pendingImportRefresh = null
        }
    }

    fun exportBookShieldRules(activity: Activity) {
        if (!registerBookShieldRulesExportHandler()) {
            showToast(activity, "内容屏蔽规则导出暂不可用")
            return
        }
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_MIME_TYPES, yamlMimeTypes)
            putExtra(Intent.EXTRA_TITLE, "QDHelper-rules-$timestamp.yaml")
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        launchPicker(activity, intent, REQUEST_EXPORT_BOOK_SHIELD_RULES, "无法打开规则文件保存位置")
    }

    fun importBookShieldRules(activity: Activity, onImported: () -> Unit) {
        if (!registerBookShieldRulesImportHandler()) {
            showToast(activity, "内容屏蔽规则导入暂不可用")
            return
        }
        pendingRuleImportRefresh = onImported
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_MIME_TYPES, yamlMimeTypes)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (!launchPicker(activity, intent, REQUEST_IMPORT_BOOK_SHIELD_RULES, "无法打开规则文件选择器")) {
            pendingRuleImportRefresh = null
        }
    }

    private fun registerExportHandler(): Boolean = registrationAvailable(
        ActivityCallbacks.registerResult(owner, REQUEST_EXPORT_CONFIG) { activity, resultCode, data ->
            handleExportResult(activity, resultCode, data)
        },
    )

    private fun registerImportHandler(): Boolean = registrationAvailable(
        ActivityCallbacks.registerResult(owner, REQUEST_IMPORT_CONFIG) { activity, resultCode, data ->
            handleImportResult(activity, resultCode, data)
        },
    )

    private fun registerBookShieldRulesExportHandler(): Boolean = registrationAvailable(
        ActivityCallbacks.registerResult(owner, REQUEST_EXPORT_BOOK_SHIELD_RULES) { activity, resultCode, data ->
            handleBookShieldRulesExportResult(activity, resultCode, data)
        },
    )

    private fun registerBookShieldRulesImportHandler(): Boolean = registrationAvailable(
        ActivityCallbacks.registerResult(owner, REQUEST_IMPORT_BOOK_SHIELD_RULES) { activity, resultCode, data ->
            handleBookShieldRulesImportResult(activity, resultCode, data)
        },
    )

    private fun registrationAvailable(result: ActivityResultRegistry.RegistrationResult): Boolean = when (result) {
        is ActivityResultRegistry.RegistrationResult.Registered,
        is ActivityResultRegistry.RegistrationResult.AlreadyRegistered -> true

        is ActivityResultRegistry.RegistrationResult.RequestCodeConflict -> false
    }

    private fun handleExportResult(activity: Activity, resultCode: Int, data: Intent?): Boolean {
        if (resultCode != Activity.RESULT_OK) {
            showToast(activity, "已取消导出")
            return true
        }
        val uri = data?.data
        if (uri == null) {
            showToast(activity, "未获取到保存位置")
            return true
        }
        showToast(activity, "正在导出配置")
        worker.execute {
            val result = writeExport(activity, uri)
            activity.runOnUiThread { showToast(activity, result.message, long = !result.success) }
        }
        return true
    }

    private fun writeExport(activity: Activity, uri: Uri): ActionResult {
        val exported = MaintenanceActions.exportConfigArchive(activity.applicationContext ?: activity)
            .getOrElse { return ActionResult(false, "导出失败：${it.message ?: it.javaClass.simpleName}") }
        return runCatching {
            val stream = activity.contentResolver.openOutputStream(uri, "wt")
                ?: error("无法打开目标文件")
            stream.use { it.write(exported.bytes) }
            ActionResult(true, "配置已导出")
        }.getOrElse { throwable ->
            ActionResult(false, "导出失败：${throwable.message ?: throwable.javaClass.simpleName}")
        }
    }

    private fun handleImportResult(activity: Activity, resultCode: Int, data: Intent?): Boolean {
        val refresh = pendingImportRefresh
        pendingImportRefresh = null
        if (resultCode != Activity.RESULT_OK) {
            showToast(activity, "已取消导入")
            return true
        }
        val uri = data?.data
        if (uri == null) {
            showToast(activity, "未获取到配置文件")
            return true
        }
        showToast(activity, "正在导入配置")
        worker.execute {
            val result = readAndImport(activity, uri)
            activity.runOnUiThread {
                showToast(activity, result.message, long = !result.success)
                if (result.success) refresh?.invoke()
            }
        }
        return true
    }

    private fun readAndImport(activity: Activity, uri: Uri): ActionResult = runCatching {
        val stream = activity.contentResolver.openInputStream(uri)
            ?: error("无法打开配置文件")
        val bytes = stream.use { input -> input.readBytesBounded(MAX_IMPORT_BYTES) }
        MaintenanceActions.importConfigArchive(activity.applicationContext ?: activity, bytes)
    }.getOrElse { throwable ->
        if (throwable is SizeLimitExceededException) {
            ActionResult(false, "导入失败：配置压缩包不能超过 2 MiB")
        } else {
            ActionResult(false, "导入失败：${throwable.message ?: throwable.javaClass.simpleName}")
        }
    }

    private fun handleBookShieldRulesExportResult(activity: Activity, resultCode: Int, data: Intent?): Boolean {
        if (resultCode != Activity.RESULT_OK) {
            showToast(activity, "已取消导出")
            return true
        }
        val uri = data?.data
        if (uri == null) {
            showToast(activity, "未获取到保存位置")
            return true
        }
        showToast(activity, "正在导出内容屏蔽规则")
        worker.execute {
            val result = writeBookShieldRulesExport(activity, uri)
            activity.runOnUiThread { showToast(activity, result.message, long = !result.success) }
        }
        return true
    }

    private fun writeBookShieldRulesExport(activity: Activity, uri: Uri): ActionResult {
        val exported = MaintenanceActions.exportBookShieldRules(activity.applicationContext ?: activity)
            .getOrElse { return ActionResult(false, "导出失败：${it.message ?: it.javaClass.simpleName}") }
        return runCatching {
            val stream = activity.contentResolver.openOutputStream(uri, "wt")
                ?: error("无法打开目标文件")
            stream.use { it.write(exported.bytes) }
            ActionResult(true, exported.message)
        }.getOrElse { throwable ->
            ActionResult(false, "导出失败：${throwable.message ?: throwable.javaClass.simpleName}")
        }
    }

    private fun handleBookShieldRulesImportResult(activity: Activity, resultCode: Int, data: Intent?): Boolean {
        val refresh = pendingRuleImportRefresh
        pendingRuleImportRefresh = null
        if (resultCode != Activity.RESULT_OK) {
            showToast(activity, "已取消导入")
            return true
        }
        val uri = data?.data
        if (uri == null) {
            showToast(activity, "未获取到规则文件")
            return true
        }
        showToast(activity, "正在导入内容屏蔽规则")
        worker.execute {
            val result = readAndImportBookShieldRules(activity, uri)
            activity.runOnUiThread {
                showToast(activity, result.message, long = !result.success)
                if (result.success) refresh?.invoke()
            }
        }
        return true
    }

    private fun readAndImportBookShieldRules(activity: Activity, uri: Uri): ActionResult = runCatching {
        val stream = activity.contentResolver.openInputStream(uri)
            ?: error("无法打开规则文件")
        val bytes = stream.use { input -> input.readBytesBounded(maxRuleImportBytes) }
        MaintenanceActions.importBookShieldRules(activity.applicationContext ?: activity, bytes)
    }.getOrElse { throwable ->
        if (throwable is SizeLimitExceededException) {
            ActionResult(false, "导入失败：规则文件不能超过 1 MiB")
        } else {
            ActionResult(false, "导入失败：${throwable.message ?: throwable.javaClass.simpleName}")
        }
    }

    private fun launchPicker(activity: Activity, intent: Intent, requestCode: Int, error: String): Boolean =
        OemFilePickerLauncher.startActivityForResult(activity, intent, requestCode).also { launched ->
            if (!launched) showToast(activity, error)
        }

    private fun showToast(activity: Activity, message: String, long: Boolean = false) {
        Toast.makeText(activity, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }
}
