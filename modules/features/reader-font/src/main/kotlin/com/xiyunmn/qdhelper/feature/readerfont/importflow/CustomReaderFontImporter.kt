package com.xiyunmn.qdhelper.feature.readerfont.importflow

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.xiyunmn.qdhelper.feature.readerfont.ui.CustomReaderFontPanelInjector
import com.xiyunmn.qdhelper.shared.io.OemFilePickerLauncher
import com.xiyunmn.qdhelper.shared.fonts.FontImportResult
import com.xiyunmn.qdhelper.shared.fonts.FontLibraryStore

internal object CustomReaderFontImporter {
    const val REQUEST_CODE = 0x514446

    private val FONT_MIME_TYPES = arrayOf(
        "font/ttf",
        "font/otf",
        "font/collection",
        "font/sfnt",
        "font/woff",
        "font/woff2",
        "application/font-sfnt",
        "application/vnd.ms-opentype",
        "application/x-font-ttf",
        "application/x-font-truetype",
        "application/x-font-otf",
        "application/x-font-opentype",
        "application/x-font-ttc",
        "application/x-font-collection",
        "application/font-woff",
        "application/font-woff2",
        "application/x-font-woff",
        "application/x-font-woff2",
    )

    fun open(activity: Activity) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, FONT_MIME_TYPES)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            putExtra(Intent.EXTRA_TITLE, "选择字体文件，可多选")
            putExtra("android.content.extra.SHOW_ADVANCED", true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (!OemFilePickerLauncher.startActivityForResult(activity, intent, REQUEST_CODE)) {
            Toast.makeText(activity, "无法打开字体选择器", Toast.LENGTH_SHORT).show()
        }
    }

    fun clear(activity: Activity) {
        val result = FontLibraryStore.clearImportedFonts(activity)
        val message = when {
            !result.settingsCleared -> "清除字体设置失败"
            !result.filesCleared -> "字体设置已清除，部分字体文件删除失败"
            else -> "已清除共享字体库、阅读与界面字体设置"
        }
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        if (result.settingsCleared) {
            CustomReaderFontPanelInjector.refreshVisible(activity)
        }
    }

    fun handleActivityResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST_CODE) {
            return false
        }
        if (resultCode != Activity.RESULT_OK) {
            Toast.makeText(activity, "未选择字体文件", Toast.LENGTH_SHORT).show()
            return true
        }
        val uris = selectedUris(data)
        if (uris.isEmpty()) {
            Toast.makeText(activity, "未获取到字体文件", Toast.LENGTH_SHORT).show()
            return true
        }
        var successCount = 0
        var lastFailure: String? = null
        uris.forEach { uri ->
            when (val result = FontLibraryStore.importFromUri(activity, uri)) {
                is FontImportResult.Success -> {
                    successCount += 1
                }
                is FontImportResult.Failure -> lastFailure = result.message
            }
        }
        val message = when {
            successCount > 1 -> "已导入 $successCount 个字体，请返回选择使用字体"
            successCount == 1 -> "已导入字体，请返回选择使用字体"
            else -> lastFailure ?: "导入字体失败"
        }
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        if (successCount > 0) {
            CustomReaderFontPanelInjector.refreshVisible(activity)
        }
        return true
    }

    private fun selectedUris(data: Intent?): List<Uri> {
        if (data == null) {
            return emptyList()
        }
        val result = mutableListOf<Uri>()
        data.data?.let(result::add)
        val clipData = data.clipData
        if (clipData != null) {
            for (index in 0 until clipData.itemCount) {
                clipData.getItemAt(index)?.uri?.let(result::add)
            }
        }
        return result.distinct()
    }
}
