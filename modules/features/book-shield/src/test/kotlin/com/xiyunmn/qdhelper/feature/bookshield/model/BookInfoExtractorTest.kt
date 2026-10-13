package com.xiyunmn.qdhelper.feature.bookshield.model

import com.xiyunmn.qdhelper.feature.bookshield.policy.BookShieldRules
import com.xiyunmn.qdhelper.feature.bookshield.policy.WordCountBlockRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookInfoExtractorTest {
    @Test
    fun `selective extraction invokes only requested book accessors`() {
        val source = CountingBook()
        val spec = BookInfoExtractionSpec(
            title = true,
            author = true,
            category = false,
            tags = false,
            type = false,
            id = false,
            words = false,
        )

        val info = BookInfoExtractor.extract(source, spec)

        assertEquals("Title", info?.title)
        assertEquals("Author", info?.author)
        assertNull(info?.category)
        assertNull(info?.id)
        assertNull(info?.words)
        assertEquals(1, source.titleReads)
        assertEquals(1, source.authorReads)
        assertEquals(0, source.categoryReads)
        assertEquals(0, source.typeReads)
        assertEquals(0, source.idReads)
        assertEquals(0, source.wordsReads)
    }

    @Test
    fun `rule extraction spec includes only fields needed for matching plus title identity`() {
        val authorRules = rules(authors = setOf("author"))
        assertTrue(authorRules.extractionSpec.title)
        assertTrue(authorRules.extractionSpec.author)
        assertFalse(authorRules.extractionSpec.category)
        assertFalse(authorRules.extractionSpec.tags)
        assertFalse(authorRules.extractionSpec.type)
        assertFalse(authorRules.extractionSpec.id)
        assertFalse(authorRules.extractionSpec.words)

        val typeAndWordsRules = rules(
            types = setOf("fantasy"),
            wordCountRule = WordCountBlockRule(atMost = 1000L, atLeast = null),
        )
        assertTrue(typeAndWordsRules.extractionSpec.title)
        assertFalse(typeAndWordsRules.extractionSpec.author)
        assertTrue(typeAndWordsRules.extractionSpec.category)
        assertTrue(typeAndWordsRules.extractionSpec.tags)
        assertTrue(typeAndWordsRules.extractionSpec.type)
        assertFalse(typeAndWordsRules.extractionSpec.id)
        assertTrue(typeAndWordsRules.extractionSpec.words)
    }

    @Test
    fun `item description is never retained while tags are extracted separately`() {
        val info = BookInfoExtractor.extract(MorphingLikeBook())

        assertEquals("Tag A", info?.tags)
        assertNull(info?.category)
        assertNull(info?.type)
    }

    @Test
    fun `numeric morphing subname is not presented as a tag`() {
        val info = BookInfoExtractor.extract(NumericMorphingLikeBook())

        assertNull(info?.tags)
    }

    @Test
    fun `numeric values inside host tag lists are ignored`() {
        val info = BookInfoExtractor.extract(NumericTagListBook())

        assertNull(info?.tags)
    }

    @Test
    fun `morphing tip yields category and word count without retaining a synopsis`() {
        assertEquals(
            MorphingTipMetadata(category = "原生幻想", words = 2_290_000L),
            parseMorphingTip("原生幻想 · 229万字"),
        )
        assertEquals(
            MorphingTipMetadata(category = "奇幻", words = 1_350_000L),
            parseMorphingTip("奇幻 · 135万"),
        )
        assertEquals(
            MorphingTipMetadata(category = "科幻", words = 125_000L),
            parseMorphingTip("推荐 · 科幻 · 12.5万字"),
        )
    }

    @Test
    fun `morphing tip rejects points numeric codes and free form text`() {
        assertNull(parseMorphingTip("2109点"))
        assertNull(parseMorphingTip("9"))
        assertNull(parseMorphingTip("这是一段书籍简介"))
        assertNull(parseMorphingTip("仙侠 · 人气新星"))
    }

    @Test
    fun `numeric host type is not presented as a category`() {
        val info = BookInfo(
            id = 1L,
            title = "Book",
            author = null,
            category = null,
            subCategory = null,
            tags = null,
            type = "0",
            typeCode = 0,
            words = null,
            sourceClass = "test",
        )

        assertNull(info.categoryForStatistics())
    }

    @Test
    fun `san jiang wrapper delegates extraction to its nested book item`() {
        val info = BookInfoExtractor.extract(
            SanJiangLikeWrapper(WrappedBook("Wrapped title", 42L)),
        )

        assertEquals("Wrapped title", info?.title)
        assertEquals(42L, info?.id)
        assertEquals(WrappedBook::class.java.name, info?.sourceClass)
    }

    private fun rules(
        authors: Set<String> = emptySet(),
        types: Set<String> = emptySet(),
        wordCountRule: WordCountBlockRule = WordCountBlockRule(null, null),
    ) = BookShieldRules(
        authors = authors,
        titleKeywords = emptySet(),
        fullTitles = emptySet(),
        types = types,
        typeContains = false,
        wordCountRule = wordCountRule,
        allowEmptyResult = false,
        bookIds = emptySet(),
    )

    private class CountingBook {
        var titleReads = 0
        var authorReads = 0
        var categoryReads = 0
        var typeReads = 0
        var idReads = 0
        var wordsReads = 0

        fun getBookName(): String = "Title".also { titleReads++ }
        fun getAuthorName(): String = "Author".also { authorReads++ }
        fun getCategoryName(): String = "Category".also { categoryReads++ }
        fun getBookType(): String = "Type".also { typeReads++ }
        fun getBookId(): Long = 123L.also { idReads++ }
        fun getWordsCount(): Long = 456L.also { wordsReads++ }
    }

    private class MorphingLikeBook {
        fun getItemName(): String = "Book"
        fun getItemSubName(): String = "Tag A"
        fun getItemDesc(): String = "This is a book synopsis that must not enter statistics."
    }

    private class NumericMorphingLikeBook {
        fun getItemName(): String = "Book"
        fun getTags(): List<Any> = emptyList()
        fun getItemSubName(): String = "9"
    }

    private class NumericTagListBook {
        fun getItemName(): String = "Book"
        fun getTags(): List<Int> = listOf(2, 9)
    }

    private class WrappedBook(
        private val title: String,
        private val id: Long,
    ) {
        fun getBookName(): String = title
        fun getBookId(): Long = id
    }

    private class SanJiangLikeWrapper(
        @JvmField val BookStoreItem: WrappedBook,
    ) {
        fun getBookStoreItem(): WrappedBook = BookStoreItem
    }
}
