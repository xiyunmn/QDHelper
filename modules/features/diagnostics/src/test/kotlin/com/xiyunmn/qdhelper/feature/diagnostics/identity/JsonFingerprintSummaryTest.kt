package com.xiyunmn.qdhelper.feature.diagnostics.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonFingerprintSummaryTest {
    @Test
    fun keysArePreservedButIdentifierValuesNeverAppear() {
        val ssaid = "a53384f9c0b21e77"
        val payload = """{"bod":"$ssaid","harmony":"0","clone":"1","kernel":"Linux version 5.10"}"""

        val summary = JsonFingerprintSummary.summarize(payload)

        // Keys are the research signal and must survive.
        assertTrue(summary.contains("bod="))
        assertTrue(summary.contains("kernel="))
        // Raw identifier values must not.
        assertFalse(summary.contains(ssaid))
        assertFalse(summary.contains("Linux version"))
        // Enumerable flags stay readable so decisions remain auditable.
        assertTrue(summary.contains("harmony=0"))
        assertTrue(summary.contains("clone=1"))
    }

    @Test
    fun nestedObjectsKeepTheirPathAndStayRedacted() {
        val payload = """{"12":{"ip":"10.0.0.7","oz":"secret-oz"},"1":"q16-raw"}"""

        val summary = JsonFingerprintSummary.summarize(payload)

        assertTrue(summary.contains("12.ip="))
        assertTrue(summary.contains("12.oz="))
        assertFalse(summary.contains("10.0.0.7"))
        assertFalse(summary.contains("secret-oz"))
        assertFalse(summary.contains("q16-raw"))
    }

    @Test
    fun sameValueYieldsSameFingerprintSoDriftIsDetectable() {
        val first = JsonFingerprintSummary.summarize("""{"dv":"device-a"}""")
        val second = JsonFingerprintSummary.summarize("""{"dv":"device-a"}""")
        val changed = JsonFingerprintSummary.summarize("""{"dv":"device-b"}""")

        assertEquals(first, second)
        assertFalse(first == changed)
    }

    @Test
    fun nonJsonPayloadFallsBackToAPlainFingerprint() {
        val summary = JsonFingerprintSummary.summarize("not-json-at-all")

        assertTrue(summary.startsWith("len=15,sha256="))
        assertFalse(summary.contains("not-json-at-all"))
    }

    @Test
    fun malformedJsonIsFingerprintedRatherThanThrowing() {
        val summary = JsonFingerprintSummary.summarize("""{"broken":""")

        assertTrue(summary.startsWith("len="))
        assertFalse(summary.contains("broken"))
    }

    @Test
    fun emptyPayloadIsReportedAsEmpty() {
        assertEquals("empty", JsonFingerprintSummary.summarize(null))
        assertEquals("empty", JsonFingerprintSummary.summarize(""))
    }

    @Test
    fun oversizedPayloadIsTruncatedInsteadOfFloodingTheLog() {
        val payload = (0 until 200).joinToString(",", "{", "}") { """"k$it":"v$it"""" }

        val summary = JsonFingerprintSummary.summarize(payload)

        assertTrue(summary.contains("…truncated"))
        assertFalse(summary.contains("v199"))
    }

    @Test
    fun arraysKeepIndexedPathsAndRedactElements() {
        val summary = JsonFingerprintSummary.summarize("""{"list":["raw-one","raw-two"]}""")

        assertTrue(summary.contains("list[0]="))
        assertTrue(summary.contains("list[1]="))
        assertFalse(summary.contains("raw-one"))
    }
}
