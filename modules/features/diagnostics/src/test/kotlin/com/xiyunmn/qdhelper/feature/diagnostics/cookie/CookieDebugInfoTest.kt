package com.xiyunmn.qdhelper.feature.diagnostics.cookie

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CookieDebugInfoTest {
    @Test
    fun displayTextMasksSensitiveValues() {
        val info = CookieDebugInfo.Info(
            userId = "123456789",
            userAgent = "QDReaderTestAgent",
            cookie = "ywkey=secret-token; sessionid=secret-session",
            androidId = "a38c1cf7a03ee407",
            qimei16 = "abcdef0123456789abcdef0123456789ab",
            qimei36 = "abcdef0123456789abcdef0123456789ab70",
            oaid = "oaid-secret-value-1234",
            ywGuid = "guid-123456",
            ywKey = "key-abcdef",
            isFirstLaunch = "false",
            isFreshman = "false",
            isLimitFree = "false",
            showLimitFreePopup = "false",
        )

        val display = info.toDisplayText()

        assertTrue(display.contains("QDReaderTestAgent"))
        assertTrue(display.contains("ywkey=<redacted>"))
        assertTrue(display.contains("sessionid=<redacted>"))
        assertTrue(display.contains("AndroidId(SSAID)"))
        assertTrue(display.contains("isLimitFree"))
        assertFalse(display.contains("123456789"))
        assertFalse(display.contains("secret-token"))
        assertFalse(display.contains("secret-session"))
        assertFalse(display.contains("a38c1cf7a03ee407"))
        assertFalse(display.contains("oaid-secret-value-1234"))
    }

    @Test
    fun fullTextKeepsOriginalValuesForExplicitCopy() {
        val info = CookieDebugInfo.Info(
            userId = "123456789",
            userAgent = "QDReaderTestAgent",
            cookie = "ywkey=secret-token; sessionid=secret-session",
            androidId = "a38c1cf7a03ee407",
            qimei16 = "qimei16-value",
            oaid = "oaid-value",
        )

        val full = info.toFullText()

        assertTrue(full.contains("123456789"))
        assertTrue(full.contains("secret-token"))
        assertTrue(full.contains("secret-session"))
        assertTrue(full.contains("a38c1cf7a03ee407"))
        assertTrue(full.contains("qimei16-value"))
        assertTrue(full.contains("oaid-value"))
    }
}
