package com.xiyunmn.qdhelper.feature.bookshield.stats

import com.xiyunmn.qdhelper.feature.bookshield.policy.ShieldReasonKind
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Test

class BookShieldStatsTest {
    @Before
    fun setUp() {
        BookShieldStats.clear()
    }

    @After
    fun tearDown() {
        BookShieldStats.clear()
    }

    @Test
    fun `recent duplicate enriches missing metadata without increasing totals`() {
        val first = event(
            timestamp = 1_000L,
            author = null,
            category = "奇幻",
            tags = null,
            words = 1_350_000L,
        )
        val richerDuplicate = event(
            timestamp = 1_500L,
            author = "作者甲",
            category = null,
            tags = "剑与魔法",
            words = null,
        )

        BookShieldStats.record(first)
        BookShieldStats.record(richerDuplicate)

        val snapshot = BookShieldStats.snapshot()
        assertEquals(1L, snapshot.totalRemoved)
        assertEquals(1L, snapshot.locationCounts["selection"])
        assertEquals(1, snapshot.events.size)
        with(snapshot.events.single()) {
            assertEquals("作者甲", author)
            assertEquals("奇幻", category)
            assertEquals("剑与魔法", tags)
            assertEquals(1_350_000L, words)
            assertEquals(1_000L, timestamp)
        }
    }

    @Test
    fun `different book ids are not merged even when titles match`() {
        BookShieldStats.record(event(timestamp = 1_000L, bookId = 1L))
        BookShieldStats.record(event(timestamp = 1_500L, bookId = 2L))

        assertEquals(2L, BookShieldStats.snapshot().totalRemoved)
    }

    private fun event(
        timestamp: Long,
        bookId: Long = 100L,
        author: String? = null,
        category: String? = null,
        tags: String? = null,
        words: Long? = null,
    ) = ShieldEvent(
        timestamp = timestamp,
        location = "selection",
        reasonKind = ShieldReasonKind.KEYWORD,
        reasonDetail = "魔法",
        bookId = bookId,
        title = "同一本书",
        author = author,
        category = category,
        tags = tags,
        words = words,
        source = "test",
    )
}
