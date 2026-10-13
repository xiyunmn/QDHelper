package com.xiyunmn.qdhelper.shared.media

import java.util.Locale

data class OriginalImageData(
    val bytes: ByteArray,
    val mimeType: String,
    val extension: String,
)

data class OriginalImageFormat(
    val mimeType: String,
    val extension: String,
)

fun detectOriginalImageFormat(
    bytes: ByteArray,
    source: String = "",
    contentType: String? = null,
): OriginalImageFormat? {
    fun startsWith(vararg values: Int): Boolean {
        return bytes.size >= values.size && values.indices.all { index ->
            bytes[index].toInt() and 0xFF == values[index]
        }
    }
    return when {
        startsWith(0xFF, 0xD8, 0xFF) -> OriginalImageFormat("image/jpeg", "jpg")
        startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) ->
            OriginalImageFormat("image/png", "png")
        startsWith(0x47, 0x49, 0x46, 0x38) -> OriginalImageFormat("image/gif", "gif")
        bytes.size >= 12 && startsWith(0x52, 0x49, 0x46, 0x46) &&
            bytes.copyOfRange(8, 12).contentEquals(byteArrayOf(0x57, 0x45, 0x42, 0x50)) ->
            OriginalImageFormat("image/webp", "webp")
        startsWith(0x42, 0x4D) -> OriginalImageFormat("image/bmp", "bmp")
        else -> formatFromMetadata(source, contentType)
    }
}

private fun formatFromMetadata(source: String, contentType: String?): OriginalImageFormat? {
    val mime = contentType
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase(Locale.US)
        ?.takeIf { it.startsWith("image/") }
    val path = source.substringBefore('?')
    return when {
        mime == "image/jpeg" || path.endsWith(".jpg", true) || path.endsWith(".jpeg", true) ->
            OriginalImageFormat("image/jpeg", "jpg")
        mime == "image/png" || path.endsWith(".png", true) -> OriginalImageFormat("image/png", "png")
        mime == "image/gif" || path.endsWith(".gif", true) -> OriginalImageFormat("image/gif", "gif")
        mime == "image/webp" || path.endsWith(".webp", true) -> OriginalImageFormat("image/webp", "webp")
        mime == "image/bmp" || path.endsWith(".bmp", true) -> OriginalImageFormat("image/bmp", "bmp")
        else -> null
    }
}
