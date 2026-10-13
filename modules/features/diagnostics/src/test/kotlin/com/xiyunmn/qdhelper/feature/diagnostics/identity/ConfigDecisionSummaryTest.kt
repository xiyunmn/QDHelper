package com.xiyunmn.qdhelper.feature.diagnostics.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigDecisionSummaryTest {
    @Test
    fun freshmanDecisionFieldsAreExtractedWithTheirPath() {
        val json = """
            {"Data":{"Freshman":{"IsFreshman":1,"IsNewDevice":1,"ShowLimitFreePopup":0}}}
        """.trimIndent()

        val summary = ConfigDecisionSummary.summarize(json)

        assertTrue(summary.contains("Data.Freshman.IsFreshman=1"))
        assertTrue(summary.contains("Data.Freshman.IsNewDevice=1"))
        assertTrue(summary.contains("Data.Freshman.ShowLimitFreePopup=0"))
    }

    @Test
    fun decisionKeysAreMatchedCaseInsensitivelyAcrossHostSpellings() {
        val summary = ConfigDecisionSummary.summarize("""{"isfreshmanlimitfree":false,"IsReceiveFreeReading":1}""")

        assertTrue(summary.contains("isfreshmanlimitfree=false"))
        assertTrue(summary.contains("IsReceiveFreeReading=1"))
    }

    @Test
    fun unrelatedPayloadContentIsNeverEmitted() {
        val json = """{"IsFreshman":1,"UserName":"reader-nickname","Token":"secret-token"}"""

        val summary = ConfigDecisionSummary.summarize(json)

        assertTrue(summary.contains("IsFreshman=1"))
        assertFalse(summary.contains("reader-nickname"))
        assertFalse(summary.contains("secret-token"))
    }

    @Test
    fun rawPayloadIsOnlyEverReportedAsAFingerprint() {
        val summary = ConfigDecisionSummary.summarize("""{"IsFreshman":1}""")

        assertTrue(summary.startsWith("raw=[len="))
        assertTrue(summary.contains("sha256="))
    }

    @Test
    fun malformedJsonReportsParseErrorWithoutThrowing() {
        val summary = ConfigDecisionSummary.summarize("""{"IsFreshman":""")

        assertTrue(summary.contains("decisions=parse_error:"))
    }

    @Test
    fun payloadWithoutDecisionKeysReportsNone() {
        assertTrue(ConfigDecisionSummary.summarize("""{"Unrelated":{"a":1}}""").contains("decisions={none}"))
    }

    @Test
    fun emptyPayloadIsReportedAsNoDecisions() {
        assertEquals("raw=[empty],decisions=none", ConfigDecisionSummary.summarize(null))
        assertEquals("raw=[empty],decisions=none", ConfigDecisionSummary.summarize(""))
    }

    @Test
    fun nestedArraysAreTraversedForDecisionFields() {
        val summary = ConfigDecisionSummary.summarize("""{"Items":[{"IsNewDevice":0},{"IsFreshman":1}]}""")

        assertTrue(summary.contains("Items[0].IsNewDevice=0"))
        assertTrue(summary.contains("Items[1].IsFreshman=1"))
    }

    @Test
    fun deeplyNestedPayloadIsBoundedRatherThanRecursingForever() {
        val depth = 40
        val json = buildString {
            repeat(depth) { append("""{"n":""") }
            append("1")
            repeat(depth) { append("}") }
        }

        // Must terminate and stay well-formed rather than overflowing the stack.
        assertTrue(ConfigDecisionSummary.summarize(json).startsWith("raw=[len="))
    }
}
