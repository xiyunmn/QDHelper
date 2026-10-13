package com.xiyunmn.qdhelper.feature.contenttools.host

import com.xiyunmn.qdhelper.shared.io.HttpRequest
import com.xiyunmn.qdhelper.shared.io.HttpTransport
import com.xiyunmn.qdhelper.shared.io.readBytesBounded
import java.io.File

internal object BookCoverCodec {
    private const val COVER_TIMEOUT_MS = 3_000
    private const val MAX_COVER_BYTES = 2 * 1024 * 1024

    fun readBytes(rawSource: String): ByteArray? {
        val source = when {
            rawSource.startsWith("//") -> "https:$rawSource"
            else -> rawSource
        }
        return runCatching {
            when {
                source.startsWith("http://", ignoreCase = true) ||
                source.startsWith("https://", ignoreCase = true) -> downloadCover(source)
                source.startsWith("file://", ignoreCase = true) ->
                    File(source.removePrefix("file://")).takeIf { it.isFile }?.inputStream()?.use {
                        it.readBytesBounded(MAX_COVER_BYTES.toLong())
                    }
                else -> File(source).takeIf { it.isFile }?.inputStream()?.use {
                    it.readBytesBounded(MAX_COVER_BYTES.toLong())
                }
            }?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun downloadCover(source: String): ByteArray? {
        return runCatching {
            HttpTransport.execute(
                HttpRequest(
                    url = source,
                    maxResponseBytes = MAX_COVER_BYTES.toLong(),
                    connectTimeoutMs = COVER_TIMEOUT_MS,
                    readTimeoutMs = COVER_TIMEOUT_MS,
                    headers = mapOf("User-Agent" to "QDReader"),
                ),
            ).body
        }.getOrNull()
    }

    fun mediaType(source: String, bytes: ByteArray): String? {
        return when {
            bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "image/jpeg"
            bytes.size >= 8 &&
                bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() -> "image/png"
            bytes.size >= 6 &&
                bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte() -> "image/gif"
            bytes.size >= 12 &&
                bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
                bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() &&
                bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() &&
                bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte() -> "image/webp"
            source.endsWith(".jpg", ignoreCase = true) || source.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
            source.endsWith(".png", ignoreCase = true) -> "image/png"
            source.endsWith(".gif", ignoreCase = true) -> "image/gif"
            source.endsWith(".webp", ignoreCase = true) -> "image/webp"
            else -> null
        }
    }

    fun extension(mediaType: String): String? {
        return when (mediaType) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            else -> null
        }
    }

}
