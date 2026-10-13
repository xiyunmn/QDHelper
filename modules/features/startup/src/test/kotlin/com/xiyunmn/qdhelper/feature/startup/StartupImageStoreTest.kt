package com.xiyunmn.qdhelper.feature.startup

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupImageStoreTest {
    @Test
    fun hostSuffixAllowsCommonImageTypesAndIgnoresQuery() {
        assertEquals(".jpg", StartupImageStore.hostSuffixOrNull("https://example.test/a.JPG?token=1"))
        assertEquals(".webp", StartupImageStore.hostSuffixOrNull("https://example.test/path/a.webp#preview"))
        assertEquals(".png", StartupImageStore.hostSuffixOrNull("/sdcard/Pictures/splash.PNG"))
    }

    @Test
    fun hostSuffixRejectsMissingOrUnexpectedExtensions() {
        assertNull(StartupImageStore.hostSuffixOrNull("https://example.test/image"))
        assertNull(StartupImageStore.hostSuffixOrNull("https://example.test/image.zip"))
        assertNull(StartupImageStore.hostSuffixOrNull("https://example.test/path.with.dot/"))
    }

    @Test
    fun sourceAcceptsHttpAndHttpsOnlyForRemote() {
        assertTrue(StartupImageStore.sourceOrNull("https://example.test/a.webp") is StartupImageSource.Remote)
        assertTrue(StartupImageStore.sourceOrNull("http://example.test/a.png") is StartupImageSource.Remote)
        assertNull(StartupImageStore.sourceOrNull("ftp://example.test/a.png"))
        assertNull(StartupImageStore.sourceOrNull("javascript://example.test/a.png"))
    }

    @Test
    fun sourceAcceptsExistingLocalFile() {
        val file = File.createTempFile("qdhelper-splash", ".png")
        try {
            val source = StartupImageStore.sourceOrNull(file.absolutePath)

            assertTrue(source is StartupImageSource.Local)
            assertEquals(".png", source?.suffix)
        } finally {
            file.delete()
        }
    }

    @Test
    fun remoteContentTypeRejectsTextButKeepsImageAndUnknownCompatible() {
        assertFalse(StartupImageStore.isRejectedRemoteContentType(null))
        assertFalse(StartupImageStore.isRejectedRemoteContentType(""))
        assertFalse(StartupImageStore.isRejectedRemoteContentType("image/png"))
        assertFalse(StartupImageStore.isRejectedRemoteContentType("image/jpeg; charset=binary"))
        assertFalse(StartupImageStore.isRejectedRemoteContentType("application/octet-stream"))

        assertTrue(StartupImageStore.isRejectedRemoteContentType("text/html"))
        assertTrue(StartupImageStore.isRejectedRemoteContentType("application/json; charset=utf-8"))
        assertTrue(StartupImageStore.isRejectedRemoteContentType("application/xml"))
    }

    @Test
    fun officialLocalSelectionRequiresPositivePaperIdAndImageSuffix() {
        assertTrue(StartupImageStore.isValidOfficialSelection(904099020562L, "https://example.test/a.webp?token=1"))
        assertFalse(StartupImageStore.isValidOfficialSelection(0L, "https://example.test/a.webp"))
        assertFalse(StartupImageStore.isValidOfficialSelection(null, "https://example.test/a.webp"))
        assertFalse(StartupImageStore.isValidOfficialSelection(904099020562L, "https://example.test/image"))
    }

    @Test
    fun officialLocalSelectionIdentityUsesPaperIdAndValidStoredUrl() {
        assertTrue(
            StartupImageStore.isSameOfficialSelection(
                904099020562L,
                "https://example.test/a.jpg",
                904099020562L,
            ),
        )
        assertFalse(
            StartupImageStore.isSameOfficialSelection(
                904099020562L,
                "https://example.test/a.jpg",
                904099020563L,
            ),
        )
        assertFalse(StartupImageStore.isSameOfficialSelection(904099020562L, "", 904099020562L))
    }
}
