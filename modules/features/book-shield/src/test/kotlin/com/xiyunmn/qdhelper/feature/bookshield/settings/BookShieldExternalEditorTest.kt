package com.xiyunmn.qdhelper.feature.bookshield.settings

import com.xiyunmn.qdhelper.config.BookShieldYamlCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BookShieldExternalEditorTest {
    @Test
    fun `external edit text accepts utf8 bom`() {
        assertEquals("version: 1", decodeExternalEditText("\uFEFFversion: 1".toByteArray()))
    }

    @Test
    fun `external edit text rejects malformed utf8`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            decodeExternalEditText(byteArrayOf(0xC3.toByte(), 0x28))
        }

        assertTrue(failure.message.orEmpty().contains("UTF-8"))
    }

    @Test
    fun `external edit text enforces yaml size limit`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            decodeExternalEditText(ByteArray(BookShieldYamlCodec.MAX_BYTES + 1))
        }

        assertTrue(failure.message.orEmpty().contains("1 MiB"))
    }
}
