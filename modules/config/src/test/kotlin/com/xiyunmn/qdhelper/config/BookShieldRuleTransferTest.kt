package com.xiyunmn.qdhelper.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BookShieldRuleTransferTest {
    @Test
    fun `rule file decoder accepts utf8 bom and normalizes yaml`() {
        val yaml = """
            version: 1
            authors: [" Author  Name ", author name]
            title_keywords: []
            full_titles: []
            types: []
            book_ids: [12, "12"]
            word_count: { at_most: 100, at_least: 200 }
        """.trimIndent()

        val content = decodeBookShieldRuleFile(("\uFEFF$yaml").toByteArray(Charsets.UTF_8))

        assertEquals(listOf("Author Name"), content.config.authors)
        assertEquals(listOf(12L), content.config.bookIds)
        assertEquals(4, content.config.ruleCount)
        assertTrue(content.text.startsWith("version: 1"))
    }

    @Test
    fun `rule file decoder rejects malformed utf8`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            decodeBookShieldRuleFile(byteArrayOf(0xC3.toByte(), 0x28))
        }

        assertTrue(failure.message.orEmpty().contains("UTF-8"))
    }

    @Test
    fun `rule file decoder rejects unsafe yaml`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            decodeBookShieldRuleFile("version: 1\nauthors: &a [x]\ntitle_keywords: *a".toByteArray())
        }

        assertTrue(failure.message.orEmpty().contains("YAML 无效"))
    }

    @Test
    fun `rule file decoder enforces one mibibyte limit`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            decodeBookShieldRuleFile(ByteArray(BookShieldRuleTransfer.MAX_FILE_BYTES + 1))
        }

        assertTrue(failure.message.orEmpty().contains("1 MiB"))
    }
}
