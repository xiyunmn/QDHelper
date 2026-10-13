package com.xiyunmn.qdhelper.shared.media

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.os.Build
import android.provider.MediaStore
import java.io.File

object ImageGalleryStore {
    fun save(context: Context, data: OriginalImageData, fileStem: String): Boolean {
        val displayName = "${safeFileStem(fileStem)}_${System.currentTimeMillis()}.${data.extension}"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, data.mimeType)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/QDHelper")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                if (context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED) return false
                @Suppress("DEPRECATION")
                val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "QDHelper")
                if (!directory.isDirectory && !directory.mkdirs()) return false
                @Suppress("DEPRECATION")
                put(MediaStore.Images.Media.DATA, File(directory, displayName).absolutePath)
            }
        }
        val uri = runCatching { resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) }.getOrNull()
            ?: return false
        return runCatching {
            resolver.openOutputStream(uri)?.use { it.write(data.bytes) } ?: error("output stream unavailable")
            if (Build.VERSION.SDK_INT >= 29) {
                resolver.update(uri, ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }, null, null)
            }
            true
        }.getOrElse {
            runCatching { resolver.delete(uri, null, null) }
            false
        }
    }

    private fun safeFileStem(raw: String): String {
        return raw.trim()
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('_', '.', '-')
            .take(64)
            .ifEmpty { "image" }
    }
}
