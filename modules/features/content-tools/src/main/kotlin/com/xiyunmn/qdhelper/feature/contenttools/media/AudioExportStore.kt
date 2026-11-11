package com.xiyunmn.qdhelper.feature.contenttools.media

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.shared.io.HttpRequest
import com.xiyunmn.qdhelper.shared.io.HttpTransport
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException

internal data class AudioExportFormat(val extension: String, val mime: String)

internal object AudioExportStore {
    private const val MAX_BYTES = 64L * 1024 * 1024

    fun downloadAndSave(context: Context, rawUrl: String, name: String, proceed: () -> Boolean) {
        val url = HttpTransport.normalizeHttpUrl(rawUrl)
        require(url != null) { "当前语音地址不可用" }
        if (!proceed()) throw CancellationException()
        val response = HttpTransport.execute(HttpRequest(
            url = url,
            maxResponseBytes = MAX_BYTES,
            connectTimeoutMs = 12_000,
            readTimeoutMs = 18_000,
            headers = mapOf("User-Agent" to "Mozilla/mobile", "Range" to "bytes=0-"),
        ))
        require(response.body.isNotEmpty()) { "下载的语音为空" }
        if (!proceed()) throw CancellationException()
        val temporary = File.createTempFile("qdhelper-voice-", ".audio", context.cacheDir)
        try {
            temporary.outputStream().use { it.write(response.body) }
            save(context, temporary, name, proceed)
        } finally {
            if (!temporary.delete()) FeatureHooks.w("Audio export temporary file cleanup failed")
        }
    }

    private fun check(file: File): AudioExportFormat {
        require(file.isFile && file.length() > 0) { "下载的语音为空" }
        require(file.length() <= MAX_BYTES) { "语音超过 64 MiB，无法导出" }
        val header = ByteArray(64)
        val size = file.inputStream().use { it.read(header) }
        fun text(offset: Int, value: String): Boolean = size >= offset + value.length &&
            value.indices.all { header[offset + it].toInt() == value[it].code }
        fun byte(index: Int) = header[index].toInt() and 255
        return when {
            text(0, "ID3") -> AudioExportFormat("mp3", "audio/mpeg")
            text(0, "#!AMR-WB\n") -> AudioExportFormat("awb", "audio/amr-wb")
            text(0, "#!AMR\n") -> AudioExportFormat("amr", "audio/amr")
            text(0, "OggS") -> AudioExportFormat("ogg", "audio/ogg")
            text(0, "RIFF") && text(8, "WAVE") -> AudioExportFormat("wav", "audio/wav")
            text(0, "fLaC") -> AudioExportFormat("flac", "audio/flac")
            text(4, "ftyp") -> AudioExportFormat("m4a", "audio/mp4")
            text(0, "ADIF") || (size >= 2 && byte(0) == 255 && (byte(1) and 0xF6) == 0xF0) ->
                AudioExportFormat("aac", "audio/aac")
            size >= 3 && byte(0) == 255 && (byte(1) and 0xE0) == 0xE0 &&
                (byte(1) and 0x18) != 0x08 && (byte(1) and 0x06) != 0 &&
                (byte(2) and 0xF0) != 0xF0 && (byte(2) and 0x0C) != 0x0C -> AudioExportFormat("mp3", "audio/mpeg")
            else -> throw IllegalArgumentException("暂不支持这段语音的格式")
        }
    }

    fun hasPermission(context: Context): Boolean = Build.VERSION.SDK_INT >= 29 ||
        context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun save(context: Context, file: File, name: String, proceed: () -> Boolean) {
        kotlin.check(hasPermission(context)) { "宿主没有存储权限，请在系统设置中授予后重试" }
        val format = check(file)
        val expectedSize = file.length()
        val stem = name.trim().replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .trim(' ', '.').take(100).ifEmpty { "章评语音" }
        val displayName = if (stem.endsWith(".${format.extension}", true)) stem else "$stem.${format.extension}"
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, format.mime)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/QDHelper")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "QDHelper")
                kotlin.check(directory.isDirectory || directory.mkdirs()) { "无法创建语音目录" }
                val target = File(directory, displayName)
                kotlin.check(!target.exists()) { "文件名已存在，请换一个名字" }
                @Suppress("DEPRECATION")
                put(MediaStore.Audio.Media.DATA, target.absolutePath)
            }
        }
        if (!proceed()) throw CancellationException()
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("无法创建音频文件")
        try {
            resolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input ->
                    val buffer = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        if (!proceed()) throw CancellationException()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        kotlin.check(total <= MAX_BYTES) { "语音超过 64 MiB，无法导出" }
                        output.write(buffer, 0, count)
                    }
                    kotlin.check(total == expectedSize) { "音频文件不完整，请重新导出" }
                }
            } ?: throw IOException("无法打开音频文件")
            if (!proceed()) throw CancellationException()
            if (Build.VERSION.SDK_INT >= 29) kotlin.check(resolver.update(uri, ContentValues().apply {
                put(MediaStore.Audio.Media.IS_PENDING, 0)
            }, null, null) > 0) { "无法完成音频保存" }
        } catch (error: Exception) {
            try { resolver.delete(uri, null, null) } catch (cleanup: Exception) {
                FeatureHooks.w("Audio export cleanup failed: ${cleanup.javaClass.simpleName}")
            }
            throw error
        }
    }
}
