package com.xiyunmn.qdhelper.shared.io

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

data class HttpRequest(
    val url: String,
    val maxResponseBytes: Long,
    val connectTimeoutMs: Int,
    val readTimeoutMs: Int,
    val headers: Map<String, String> = emptyMap(),
    val followRedirects: Boolean = true,
)

data class HttpResponse(
    val statusCode: Int,
    val contentType: String?,
    val body: ByteArray,
)

class HttpStatusException(val statusCode: Int) : IOException("Unexpected HTTP status $statusCode")

object HttpTransport {
    fun execute(request: HttpRequest): HttpResponse {
        require(request.maxResponseBytes >= 0L)
        require(request.connectTimeoutMs > 0 && request.readTimeoutMs > 0)
        val source = normalizeHttpUrl(request.url) ?: throw IOException("Only HTTP(S) URLs are supported")
        val connection = (URL(source).openConnection() as? HttpURLConnection)
            ?: throw IOException("URL is not an HTTP connection")
        connection.connectTimeout = request.connectTimeoutMs
        connection.readTimeout = request.readTimeoutMs
        connection.instanceFollowRedirects = request.followRedirects
        request.headers.forEach(connection::setRequestProperty)
        return try {
            val status = connection.responseCode
            if (status !in 200..299) throw HttpStatusException(status)
            val declaredLength = connection.contentLengthLong
            if (declaredLength > request.maxResponseBytes) {
                throw SizeLimitExceededException(request.maxResponseBytes)
            }
            val body = connection.inputStream.use { it.readBytesBounded(request.maxResponseBytes) }
            HttpResponse(status, connection.contentType, body)
        } finally {
            connection.disconnect()
        }
    }

    fun normalizeHttpUrl(rawUrl: String): String? {
        val value = rawUrl.trim()
        val normalized = if (value.startsWith("//")) "https:$value" else value
        val scheme = runCatching { URI(normalized).scheme?.lowercase() }.getOrNull()
        return normalized.takeIf { scheme == "http" || scheme == "https" }
    }
}
