package com.xiyunmn.qdhelper.shared.media

import com.xiyunmn.qdhelper.shared.io.HttpRequest
import com.xiyunmn.qdhelper.shared.io.HttpTransport

object RemoteImageDownloader {
    private const val MAX_DOWNLOAD_BYTES = 32 * 1024 * 1024

    fun download(rawUrl: String): OriginalImageData? {
        return runCatching { downloadChecked(rawUrl) }.getOrNull()
    }

    private fun downloadChecked(rawUrl: String): OriginalImageData? {
        val source = HttpTransport.normalizeHttpUrl(rawUrl) ?: return null
        val response = HttpTransport.execute(
            HttpRequest(
                url = source,
                maxResponseBytes = MAX_DOWNLOAD_BYTES.toLong(),
                connectTimeoutMs = 12_000,
                readTimeoutMs = 18_000,
                headers = mapOf("User-Agent" to "QDReader"),
            ),
        )
        val format = detectOriginalImageFormat(response.body, source, response.contentType) ?: return null
        return OriginalImageData(response.body, format.mimeType, format.extension)
    }
}
