package com.xiyunmn.qdhelper.feature.diagnostics.cookie

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostSecurityDiagnosticsTest {
    @Test
    fun bootloaderSummaryDistinguishesLockedAndUnlockedEvidence() {
        assertEquals(
            "已锁定",
            HostSecurityDiagnostics.summarizeBootloader(
                mapOf(
                    "RO_SECUREBOOT_LOCKSTATE" to "locked",
                    "RO_BOOT_FLASH_LOCKED" to "1",
                    "RO_BOOT_VERIFIEDSTATE" to "green",
                ),
            ),
        )
        assertEquals(
            "已解锁/存在解锁证据",
            HostSecurityDiagnostics.summarizeBootloader(
                mapOf(
                    "RO_SECUREBOOT_LOCKSTATE" to "locked",
                    "RO_BOOT_FLASH_LOCKED" to "0",
                    "ANDROIDBOOT_VERIFIEDSTATE" to "orange",
                ),
            ),
        )
    }

    @Test
    fun rootSummaryUsesHostDetectorSignals() {
        assertEquals(
            "检测到 root 信号",
            HostSecurityDiagnostics.summarizeRoot(
                mapOf("IS_ROOT_BY_PATH" to "0", "IS_ROOT_BY_MAGISK" to "1"),
            ),
        )
        assertEquals(
            "未检测到 root 信号",
            HostSecurityDiagnostics.summarizeRoot(
                mapOf("IS_ROOT_BY_PATH" to "0", "IS_ROOT_BY_MAGISK" to "false"),
            ),
        )
    }

    @Test
    fun displayMasksSensitiveDiagnosticFieldsByDefault() {
        val info = HostSecurityDiagnostics.Info(
            available = true,
            bootloader = listOf(HostSecurityDiagnostics.Entry("RO_BOOT_FLASH_LOCKED", "1", false)),
            root = listOf(HostSecurityDiagnostics.Entry("IS_ROOT_BY_MAGISK", "0", false)),
            diagnostics = listOf(HostSecurityDiagnostics.Entry("QIMEI", "qimei-secret-value", true)),
        )
        val display = info.toText(maskSensitive = true)
        assertTrue(display.contains("Bootloader 锁状态：已锁定"))
        assertTrue(display.contains("Root 环境：未检测到 root 信号"))
        assertTrue(display.contains("QIMEI: qi****ue"))
        assertTrue(info.toText(maskSensitive = false).contains("qimei-secret-value"))
    }
}
