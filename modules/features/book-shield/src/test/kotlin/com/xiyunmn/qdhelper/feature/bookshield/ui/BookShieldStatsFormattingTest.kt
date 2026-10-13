package com.xiyunmn.qdhelper.feature.bookshield.ui

import com.xiyunmn.qdhelper.feature.bookshield.policy.ShieldReasonKind
import com.xiyunmn.qdhelper.feature.bookshield.stats.ShieldEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class BookShieldStatsFormattingTest {
    @Test
    fun `metadata uses the required order`() {
        val event = event(
            author = "Author",
            words = 123456L,
            category = "Fantasy",
            tags = "Magic、Adventure",
            bookId = 10001L,
        )

        assertEquals(
            listOf(
                "作者 Author",
                "字数 12.3万字",
                "分类 Fantasy",
                "标签 Magic、Adventure",
                "书号 10001",
            ),
            bookShieldMetadataParts(event),
        )
    }

    @Test
    fun `missing metadata fields are omitted without placeholders`() {
        val event = event(
            author = null,
            words = 0L,
            category = "Fantasy",
            tags = null,
            bookId = null,
        )

        assertEquals(listOf("分类 Fantasy"), bookShieldMetadataParts(event))
    }

    private fun event(
        author: String?,
        words: Long?,
        category: String?,
        tags: String?,
        bookId: Long?,
    ) = ShieldEvent(
        timestamp = 1L,
        location = "test",
        reasonKind = ShieldReasonKind.KEYWORD,
        reasonDetail = "keyword",
        bookId = bookId,
        title = "Book",
        author = author,
        category = category,
        tags = tags,
        words = words,
        source = "test",
    )
}
