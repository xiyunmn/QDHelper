package com.xiyunmn.qdhelper.foundation.logging

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeLogTest {
    @Test
    fun redactsSensitiveKeyValues() {
        val safe = SafeLog.sanitize("userId=123456 token:abcdef deviceId=dev-001 qimei36=secret")

        assertFalse(safe.contains("123456"))
        assertFalse(safe.contains("abcdef"))
        assertFalse(safe.contains("dev-001"))
        assertFalse(safe.contains("secret"))
        assertTrue(safe.contains("userId=<redacted>"))
        assertTrue(safe.contains("token:<redacted>"))
    }

    @Test
    fun redactsUrlQueryValues() {
        val safe = SafeLog.sanitize("GET https://example.test/path?a=1&token=abc&name=book")

        assertTrue(safe.contains("?a=<redacted>"))
        assertTrue(safe.contains("&token=<redacted>"))
        assertTrue(safe.contains("&name=<redacted>"))
        assertFalse(safe.contains("abc"))
    }

    @Test
    fun redactsLongBase64LikeBlobs() {
        val blob = "A".repeat(96)
        val safe = SafeLog.sanitize("content=$blob")

        assertFalse(safe.contains(blob))
        assertTrue(safe.contains("<redacted_blob>"))
    }

}
