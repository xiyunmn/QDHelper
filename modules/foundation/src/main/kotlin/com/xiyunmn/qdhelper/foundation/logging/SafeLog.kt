package com.xiyunmn.qdhelper.foundation.logging

object SafeLog {
    fun sanitize(message: String): String {
        return message
            .replace(SENSITIVE_KEY_VALUE) { match ->
                "${match.groupValues[1]}${match.groupValues[2]}<redacted>"
            }
            .replace(URL_QUERY_VALUE) { match ->
                "${match.groupValues[1]}<redacted>"
            }
            .replace(LONG_BLOB, "<redacted_blob>")
    }

    private val SENSITIVE_KEY_VALUE = Regex(
        "(?i)\\b(cookie|token|session|captcha|userId|user_id|deviceId|device_id|qimei|qimei36|imei|oaid|android_id|androidId)\\b(\\s*[=:]\\s*)[^,\\s&]+",
    )
    private val URL_QUERY_VALUE = Regex("([?&][A-Za-z0-9_.%-]{1,64}=)[^&\\s]+")
    private val LONG_BLOB = Regex("\\b[A-Za-z0-9+/]{80,}={0,2}\\b")
}
