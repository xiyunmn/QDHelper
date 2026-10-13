package com.xiyunmn.qdhelper.feature.maintenance

import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaintenanceFilePolicyTest {
    @Test
    fun keepsLoginAndAccountRoots() {
        assertTrue(MaintenanceFilePolicy.shouldKeepDataRoot("shared_prefs"))
        assertTrue(MaintenanceFilePolicy.shouldKeepDataRoot("files"))
        assertTrue(MaintenanceFilePolicy.shouldKeepDataRoot("app_webview"))
        assertTrue(MaintenanceFilePolicy.shouldKeepDataRoot("mmkv_user_login"))
        assertTrue(MaintenanceFilePolicy.shouldKeepDataRoot("passport_cache"))
    }

    @Test
    fun deletesOnlyKnownRebuildableRoots() {
        assertTrue(MaintenanceFilePolicy.shouldDeleteDataRoot("app_adnet_cache"))
        assertTrue(MaintenanceFilePolicy.shouldDeleteDataRoot("app_tbs"))
        assertTrue(MaintenanceFilePolicy.shouldDeleteDataRoot("app_crash_report"))
        assertFalse(MaintenanceFilePolicy.shouldDeleteDataRoot("databases"))
        assertFalse(MaintenanceFilePolicy.shouldDeleteDataRoot("cache"))
        assertFalse(MaintenanceFilePolicy.shouldDeleteDataRoot("random_dir"))
    }

    @Test
    fun deletePathInsideRejectsOutsideTarget() {
        val root = Files.createTempDirectory("qdhelper-root").toFile()
        val outside = Files.createTempDirectory("qdhelper-outside").toFile()
        try {
            val target = outside.resolve("victim.txt").apply { writeText("keep") }

            assertFalse(MaintenanceFilePolicy.deletePathInside(root, target))
            assertTrue(target.exists())
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test
    fun deletePathInsideDeletesInsideTarget() {
        val root = Files.createTempDirectory("qdhelper-root").toFile()
        try {
            val target = root.resolve("app_tbs").apply {
                mkdirs()
                resolve("cache.bin").writeText("delete")
            }

            assertTrue(MaintenanceFilePolicy.deletePathInside(root, target))
            assertFalse(target.exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
