package com.xiyunmn.qdhelper.feature.diagnostics.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class IdentityEvidenceCodecTest {
    @Test
    fun fingerprintIsStableOneWayAndDoesNotExposeTail() {
        val value = "identity-secret-a70c"
        val first = IdentityEvidenceCodec.fingerprint(value)
        val second = IdentityEvidenceCodec.fingerprint(value)

        assertEquals(first, second)
        assertTrue(first.startsWith("len=${value.length},sha256="))
        assertFalse(first.contains("a70c"))
        assertNotEquals(first, IdentityEvidenceCodec.fingerprint("identity-secret-a70d"))
        assertEquals("empty", IdentityEvidenceCodec.fingerprint(""))
    }

    @Test
    fun qdInfoParserExtractsStableIdentitySlotsAndSource() {
        val fields = MutableList(20) { index -> "slot$index" }
        fields[0] = "qimei16-value"
        fields[11] = "123456"
        fields[12] = "1700000000000"
        fields[14] = "qimei16-value"
        fields[15] = "ssaid-value"
        fields[17] = "oaid-value"
        fields[18] = "qimei36-value"

        val summary = IdentityEvidenceCodec.parseQdInfo(fields.joinToString("|"), "qimei16-value")

        assertNotNull(summary)
        requireNotNull(summary)
        assertEquals(20, summary.fieldCount)
        assertEquals(1700000000000L, summary.timestamp)
        assertEquals(true, summary.qimeiArgumentMatches)
        assertEquals("qimei16", summary.cyidSource)
        assertFalse(summary.asLogString().contains("qimei16-value"))
        assertFalse(summary.asLogString().contains("ssaid-value"))
    }

    @Test
    fun qdInfoParserRejectsCiphertextAndInvalidTimestamp() {
        assertNull(IdentityEvidenceCodec.parseQdInfo("ciphertext", null))
        val fields = MutableList(20) { "x" }
        fields[12] = "not-a-timestamp"
        assertNull(IdentityEvidenceCodec.parseQdInfo(fields.joinToString("|"), null))
    }

    @Test
    fun fileFingerprintContainsMetadataAndDigestOnly() {
        val file = File.createTempFile("identity-evidence", ".json")
        try {
            file.writeText("{\"IsNewDevice\":1}")
            val fingerprint = IdentityEvidenceCodec.fileFingerprint(file)
            assertTrue(fingerprint.contains("size=${file.length()}"))
            assertTrue(fingerprint.contains("sha256="))
            assertFalse(fingerprint.contains("IsNewDevice"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun safeFlagsRemainReadableButOtherStringsAreHashed() {
        assertEquals("true", IdentityEvidenceCodec.safeFlag("TRUE"))
        assertEquals("m", IdentityEvidenceCodec.safeFlag("m"))
        assertTrue(IdentityEvidenceCodec.safeFlag("100,104").startsWith("len=7,sha256="))
    }

}
