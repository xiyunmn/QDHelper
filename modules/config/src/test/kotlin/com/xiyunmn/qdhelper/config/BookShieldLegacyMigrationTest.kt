package com.xiyunmn.qdhelper.config

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class BookShieldLegacyMigrationTest {
    @Test
    fun `managed yaml uses short name beside the shared asset directories`() {
        val filesDir = File("host-files")

        assertEquals(
            File(filesDir, "qdhelper/book-shield/rules.yaml"),
            managedBookShieldRuleFile(filesDir),
        )
    }

    @Test
    fun `legacy values normalize and discard invalid ids and overlapping bounds`() {
        val config = legacyRuleConfig(
            mapOf(
                LEGACY_BOOK_SHIELD_AUTHORS to " Author  Name,author name 作者甲 ",
                LEGACY_BOOK_SHIELD_BOOK_IDS to "12 bad -1 12",
                LEGACY_BOOK_SHIELD_WORDS_AT_MOST to "200",
                LEGACY_BOOK_SHIELD_WORDS_AT_LEAST to "100",
            ),
        )

        assertEquals(listOf("Author", "Name", "作者甲"), config.authors)
        assertEquals(listOf(12L), config.bookIds)
        assertEquals(null, config.wordsAtMost)
        assertEquals(null, config.wordsAtLeast)
    }

    @Test
    fun `migration merges existing yaml without replacing its first spelling`() {
        val merged = mergeMigratedRules(
            existing = BookShieldRuleConfig(authors = listOf("Author Name"), wordsAtMost = 100L),
            legacy = BookShieldRuleConfig(
                authors = listOf("author name", "作者甲"),
                bookIds = listOf(12L),
                wordsAtLeast = 1_000L,
            ),
        )

        assertEquals(listOf("Author Name", "作者甲"), merged.authors)
        assertEquals(listOf(12L), merged.bookIds)
        assertEquals(100L, merged.wordsAtMost)
        assertEquals(1_000L, merged.wordsAtLeast)
    }

    @Test
    fun `cross source bound conflict keeps existing bounds without dropping legacy lists`() {
        val merged = mergeMigratedRules(
            existing = BookShieldRuleConfig(authors = listOf("现有作者"), wordsAtMost = 500L),
            legacy = BookShieldRuleConfig(authors = listOf("旧作者"), bookIds = listOf(12L), wordsAtLeast = 100L),
        )

        assertEquals(listOf("现有作者", "旧作者"), merged.authors)
        assertEquals(listOf(12L), merged.bookIds)
        assertEquals(500L, merged.wordsAtMost)
        assertEquals(null, merged.wordsAtLeast)
    }
}
