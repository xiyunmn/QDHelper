package com.xiyunmn.qdhelper.feature.diagnostics.cookie

import org.junit.Assert.assertEquals
import org.junit.Test

class TianyuAttestationDiagnosticsTest {
    @Test
    fun verifiedBootStateLabelsMatchAndroidKeyAttestationValues() {
        assertEquals("Verified（0）", TianyuAttestationDiagnostics.verifiedBootStateLabel(0))
        assertEquals("SelfSigned（1）", TianyuAttestationDiagnostics.verifiedBootStateLabel(1))
        assertEquals("Unverified（2）", TianyuAttestationDiagnostics.verifiedBootStateLabel(2))
        assertEquals("Failed（3）", TianyuAttestationDiagnostics.verifiedBootStateLabel(3))
        assertEquals("未知（99）", TianyuAttestationDiagnostics.verifiedBootStateLabel(99))
    }

    @Test
    fun securityAndPatchLabelsMatchAttestationEncoding() {
        assertEquals("Software（0）", TianyuAttestationDiagnostics.securityLevelLabel(0))
        assertEquals("TEE（1）", TianyuAttestationDiagnostics.securityLevelLabel(1))
        assertEquals("StrongBox（2）", TianyuAttestationDiagnostics.securityLevelLabel(2))
        assertEquals("16.0.0（160000）", TianyuAttestationDiagnostics.osVersionLabel(160000))
        assertEquals("2026-08（202608）", TianyuAttestationDiagnostics.patchLevelLabel(202608))
        assertEquals("2026-08-05（20260805）", TianyuAttestationDiagnostics.patchLevelLabel(20260805))
    }

    @Test
    fun unavailableInfoExplainsReadOnlyBehavior() {
        val text = TianyuAttestationDiagnostics.Info.unavailable("测试环境无 AndroidKeyStore")
            .toText()
        assertEquals(true, text.contains("测试环境无 AndroidKeyStore"))
        assertEquals(true, text.contains("未主动生成密钥或发起风险请求"))
    }

    @Test
    fun certificateExtensionStringsAreMaskedByDefault() {
        val info = TianyuAttestationDiagnostics.Info(
            available = true,
            certificateCount = 2,
            extensionOid = "1.2.3.4",
            extensionEntries = listOf(
                TianyuAttestationDiagnostics.ExtensionEntry("code", "0", false),
                TianyuAttestationDiagnostics.ExtensionEntry("payload", "secret-value", true),
            ),
        )

        assertEquals(true, info.toText().contains("code: 0"))
        assertEquals(true, info.toText().contains("payload: <redacted>"))
        assertEquals(false, info.toText().contains("secret-value"))
        assertEquals(true, info.toText(maskSensitive = false).contains("secret-value"))
    }
}
