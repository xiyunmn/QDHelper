package com.xiyunmn.qdhelper.feature.contenttools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardPreviewPolicyTest {
    @Test
    fun acceptsOnlyExactQidianCardPreviewUrls() {
        assertTrue(
            CardPreviewPolicy.isPreviewUrl(
                "https://h5.if.qidian.com/h5/roleCard/roleCardPreview?cardId=1",
            ),
        )
        assertTrue(
            CardPreviewPolicy.isPreviewUrl(
                "https://H5.IF.QIDIAN.COM/h5/roleCard/specialCardPreview/#card",
            ),
        )

        assertFalse(CardPreviewPolicy.isPreviewUrl("http://h5.if.qidian.com/h5/roleCard/roleCardPreview"))
        assertFalse(CardPreviewPolicy.isPreviewUrl("https://h5.if.qidian.com.evil.test/h5/roleCard/roleCardPreview"))
        assertFalse(CardPreviewPolicy.isPreviewUrl("https://h5.if.qidian.com/h5/roleCard/roleCardPreview/extra"))
        assertFalse(CardPreviewPolicy.isPreviewUrl("https://h5.if.qidian.com/h5/roleCard/cardPlaza"))
        assertFalse(CardPreviewPolicy.isPreviewUrl("QDReader://app/openRoleCardMainPage"))
        assertFalse(CardPreviewPolicy.isPreviewUrl(null))
    }

    @Test
    fun parsesDirectAndEvaluateJavascriptEncodedSaveTargets() {
        val direct = CardPreviewPolicy.parseSaveTarget(
            """{"url":"https://img.qidian.com/card.webp","cardId":"42","cardName":"青龙"}""",
        )
        assertEquals("https://img.qidian.com/card.webp", direct?.url)
        assertEquals("42", direct?.cardId)
        assertEquals("青龙", direct?.cardName)

        val encoded = CardPreviewPolicy.parseSaveTarget(
            """"{\"url\":\"https://img.qidian.com/special.png\",\"cardId\":\"7\",\"cardName\":\"专题卡\"}""""",
        )
        assertEquals("https://img.qidian.com/special.png", encoded?.url)
        assertEquals("7", encoded?.cardId)
        assertEquals("专题卡", encoded?.cardName)

        val protocolRelative = CardPreviewPolicy.parseSaveTarget(
            """{"url":"//img.qidian.com/protocol-relative.webp"}""",
        )
        assertEquals("https://img.qidian.com/protocol-relative.webp", protocolRelative?.url)
    }

    @Test
    fun rejectsMissingOrUnsafeSaveTargets() {
        assertNull(CardPreviewPolicy.parseSaveTarget(null))
        assertNull(CardPreviewPolicy.parseSaveTarget("null"))
        assertNull(CardPreviewPolicy.parseSaveTarget("{}"))
        assertNull(CardPreviewPolicy.parseSaveTarget("not-json"))
        assertNull(CardPreviewPolicy.parseSaveTarget("""{"url":"file:///data/local/tmp/card.png"}"""))
        assertNull(CardPreviewPolicy.parseSaveTarget("""{"url":"javascript:alert(1)"}"""))
        assertNull(CardPreviewPolicy.parseSaveTarget("""{"url":"https:///missing-host.png"}"""))
    }
}
