package com.xiyunmn.qdhelper.feature.diagnostics.cookie

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TianyuDiagnosticsTest {
    @Test
    fun outputIncludesEveryReadOnlyResultGroupAndMasksSensitiveMaps() {
        val info = TianyuDiagnostics.Info(
            available = true,
            sdkPresent = true,
            sdkVersion = "TuringFD v2.92.2",
            libraryLoaded = true,
            initialized = true,
            nativeInitialized = true,
            attestation = TianyuAttestationDiagnostics.Info.unavailable("无现有证书"),
            cachedState = listOf(TianyuDiagnostics.Entry("状态版本", "9")),
            detectionResults = listOf(
                TianyuDiagnostics.Entry("策略项[40]", "0"),
                TianyuDiagnostics.Entry("策略附加字段[path]", "/private/value", sensitive = true),
            ),
            configuredDetectors = listOf(TianyuDiagnostics.Entry("当前检测集合", "36, 40")),
            cachedRisk = listOf(TianyuDiagnostics.Entry("缓存 code", "0")),
        )

        val masked = info.toText(maskSensitive = true)
        val full = info.toText(maskSensitive = false)

        assertTrue(masked.contains("天御服务端检测策略原始值"))
        assertTrue(masked.contains("策略项[40]: 0"))
        assertTrue(masked.contains("策略附加字段[path]: <redacted>"))
        assertTrue(masked.contains("当前检测集合: 36, 40"))
        assertTrue(masked.contains("缓存 code: 0"))
        assertFalse(masked.contains("/private/value"))
        assertTrue(full.contains("/private/value"))
        assertTrue(full.contains("SDK 未公开或缓存"))
    }
}
