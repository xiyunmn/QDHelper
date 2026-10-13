package com.xiyunmn.qdhelper.feature.bookshield.policy

import com.xiyunmn.qdhelper.feature.bookshield.model.BookInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WordCountBlockRuleTest {
    @Test
    fun `empty bounds disable word filtering`() {
        val rule = WordCountBlockRule.parse("", "  ")

        assertFalse(rule.isEnabled)
        assertNull(rule.validationError)
        assertNull(rule.match(100L))
    }

    @Test
    fun `lower bound blocks words at or below threshold`() {
        val rule = WordCountBlockRule.parse("100", "")

        assertTrue(rule.isEnabled)
        assertEquals(WordCountMatch.TooShort(99L, 100L), rule.match(99L))
        assertEquals(WordCountMatch.TooShort(100L, 100L), rule.match(100L))
        assertNull(rule.match(101L))
    }

    @Test
    fun `upper bound blocks words at or above threshold`() {
        val rule = WordCountBlockRule.parse("", "1000")

        assertTrue(rule.isEnabled)
        assertNull(rule.match(999L))
        assertEquals(WordCountMatch.TooLong(1000L, 1000L), rule.match(1000L))
        assertEquals(WordCountMatch.TooLong(1001L, 1000L), rule.match(1001L))
    }

    @Test
    fun `two bounds preserve only their exclusive middle range`() {
        val rule = WordCountBlockRule.parse("100", "1000")

        assertEquals(WordCountMatch.TooShort(100L, 100L), rule.match(100L))
        assertNull(rule.match(101L))
        assertNull(rule.match(999L))
        assertEquals(WordCountMatch.TooLong(1000L, 1000L), rule.match(1000L))
    }

    @Test
    fun `unknown and non-positive word counts are always allowed`() {
        val rule = WordCountBlockRule.parse("100", "1000")

        assertNull(rule.match(null))
        assertNull(rule.match(0L))
        assertNull(rule.match(-1L))
    }

    @Test
    fun `invalid bound text and non-positive thresholds fail open`() {
        listOf(
            WordCountBlockRule.parse("not-a-number", ""),
            WordCountBlockRule.parse("0", ""),
            WordCountBlockRule.parse("-1", ""),
            WordCountBlockRule.parse("", "not-a-number"),
        ).forEach { rule ->
            assertFalse(rule.isEnabled)
            assertTrue(rule.validationError != null)
            assertNull(rule.match(1L))
            assertNull(rule.match(Long.MAX_VALUE))
        }
    }

    @Test
    fun `overlapping bounds are invalid and fail open`() {
        listOf(
            WordCountBlockRule.parse("100", "100"),
            WordCountBlockRule.parse("101", "100"),
        ).forEach { rule ->
            assertFalse(rule.isEnabled)
            assertEquals("下侧阈值必须小于上侧阈值", rule.validationError)
            assertNull(rule.match(100L))
        }
    }

    @Test
    fun `book rules report distinct short and long reasons`() {
        val rules = BookShieldRules(
            authors = emptySet(),
            titleKeywords = emptySet(),
            fullTitles = emptySet(),
            types = emptySet(),
            typeContains = false,
            wordCountRule = WordCountBlockRule.parse("100", "1000"),
            allowEmptyResult = false,
            bookIds = emptySet(),
        )

        assertEquals(ShieldReasonKind.WORDS_TOO_SHORT, rules.matchReason(book(words = 100L))?.kind)
        assertEquals(ShieldReasonKind.WORDS_TOO_LONG, rules.matchReason(book(words = 1000L))?.kind)
        assertNull(rules.matchReason(book(words = 500L)))
    }

    @Test
    fun `invalid rule does not request word extraction`() {
        val invalid = rulesWith(WordCountBlockRule.parse("100", "100"))

        assertFalse(invalid.hasRules)
        assertFalse(invalid.extractionSpec.words)
    }

    private fun rulesWith(rule: WordCountBlockRule) = BookShieldRules(
        authors = emptySet(),
        titleKeywords = emptySet(),
        fullTitles = emptySet(),
        types = emptySet(),
        typeContains = false,
        wordCountRule = rule,
        allowEmptyResult = false,
        bookIds = emptySet(),
    )

    private fun book(words: Long) = BookInfo(
        id = null,
        title = "Book",
        author = null,
        category = null,
        subCategory = null,
        tags = null,
        type = null,
        typeCode = null,
        words = words,
        sourceClass = "test",
    )
}
