package com.xiyunmn.qdhelper.shared.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OriginalImageDataTest {
    @Test
    fun detectsImageFormatsFromSignatures() {
        assertEquals(
            OriginalImageFormat("image/jpeg", "jpg"),
            detectOriginalImageFormat(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00)),
        )
        assertEquals(
            OriginalImageFormat("image/png", "png"),
            detectOriginalImageFormat(
                byteArrayOf(
                    0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                ),
            ),
        )
        assertEquals(
            OriginalImageFormat("image/webp", "webp"),
            detectOriginalImageFormat(
                byteArrayOf(
                    0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00,
                    0x57, 0x45, 0x42, 0x50,
                ),
            ),
        )
    }

    @Test
    fun usesTrustedImageMetadataWhenSignatureIsUnavailable() {
        assertEquals(
            OriginalImageFormat("image/webp", "webp"),
            detectOriginalImageFormat(byteArrayOf(1, 2, 3), "https://example.test/avatar.webp?size=1"),
        )
        assertEquals(
            OriginalImageFormat("image/png", "png"),
            detectOriginalImageFormat(byteArrayOf(1, 2, 3), contentType = "image/png; charset=binary"),
        )
        assertNull(detectOriginalImageFormat(byteArrayOf(1, 2, 3), contentType = "text/plain"))
    }

    @Test
    fun downloaderRejectsUnsupportedSourcesWithoutThrowing() {
        assertNull(RemoteImageDownloader.download("file:///tmp/avatar.png"))
        assertNull(RemoteImageDownloader.download("not a url"))
    }
}
