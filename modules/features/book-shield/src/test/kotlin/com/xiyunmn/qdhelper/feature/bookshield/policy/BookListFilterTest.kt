package com.xiyunmn.qdhelper.feature.bookshield.policy

import com.qidian.morphing.MorphingCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BookListFilterTest {
    @Test
    fun `filters matching leaf books and keeps list order`() {
        val kept = TestBook("Keep")
        val blocked = TestBook("Blocked title")

        val result = BookListFilter.filterSourceWithRules(
            location = "test",
            source = arrayListOf(kept, blocked),
            rules = rules(titleKeywords = setOf("blocked")),
        ) as List<*>

        assertEquals(listOf(kept), result)
    }

    @Test
    fun `empty result guard returns the original list identity`() {
        val original = arrayListOf(TestBook("Blocked title"))

        val result = BookListFilter.filterSourceWithRules(
            location = "test",
            source = original,
            rules = rules(titleKeywords = setOf("blocked"), allowEmptyResult = false),
        )

        assertSame(original, result)
    }

    @Test
    fun `more reading can remove a matching single-item morphing list`() {
        val original = arrayListOf(TestBook("Blocked title"))

        val result = BookListFilter.filterSourceWithRules(
            location = BookShieldLocations.READ_LAST_PAGE_READ_MORE,
            source = original,
            rules = rules(titleKeywords = setOf("blocked"), allowEmptyResult = false),
        ) as List<*>

        assertEquals(emptyList<Any?>(), result)
    }

    @Test
    fun `waterfall removes the whole morphing card for a matching singleton book`() {
        val kept = MorphingCard(MorphingData(listOf(TestBook("Keep"))))
        val blocked = MorphingCard(MorphingData(listOf(TestBook("Blocked title"))))

        val result = BookListFilter.filterSourceWithRules(
            location = BookShieldLocations.SELECTION_MORPHING,
            source = arrayListOf(kept, blocked),
            rules = rules(titleKeywords = setOf("blocked"), allowEmptyResult = false),
        ) as List<*>

        assertEquals(listOf(kept), result)
    }

    @Test
    fun `waterfall still keeps the original batch when every card is blocked`() {
        val blocked = MorphingCard(MorphingData(listOf(TestBook("Blocked title"))))
        val original = arrayListOf(blocked)

        val result = BookListFilter.filterSourceWithRules(
            location = BookShieldLocations.BOOKSTORE_REBORN,
            source = original,
            rules = rules(titleKeywords = setOf("blocked"), allowEmptyResult = false),
        )

        assertSame(original, result)
    }

    @Test
    fun `titled container is still traversed when it exposes a nested list accessor`() {
        val kept = TestBook("Keep")
        val container = TitledContainer(arrayListOf(kept, TestBook("Blocked title")))
        val original = arrayListOf(container)

        val result = BookListFilter.filterSourceWithRules(
            location = "test",
            source = original,
            rules = rules(titleKeywords = setOf("blocked")),
        )

        assertSame(original, result)
        assertEquals(listOf(kept), container.currentItems)
    }

    @Test
    fun `identity traversal guard breaks cyclic containers`() {
        val cyclic = CyclicContainer()
        val original = arrayListOf(cyclic)

        val result = BookListFilter.filterSourceWithRules(
            location = "test",
            source = original,
            rules = rules(titleKeywords = setOf("blocked")),
        )

        assertSame(original, result)
    }

    @Test
    fun `shared container instance is traversed only once per top level filter`() {
        val container = TitledContainer(arrayListOf(TestBook("Keep")))
        val original = arrayListOf(container, container)

        BookListFilter.filterSourceWithRules(
            location = "test",
            source = original,
            rules = rules(titleKeywords = setOf("blocked")),
        )

        assertEquals(1, container.itemReads)
    }

    @Test
    fun `san jiang wrapper is removed while structural rows remain`() {
        val header = SanJiangLikeRow(null)
        val kept = SanJiangLikeRow(TestBook("Keep"))
        val blocked = SanJiangLikeRow(TestBook("Blocked title"))

        val result = BookListFilter.filterSourceWithRules(
            location = BookShieldLocations.BOOKSTORE_REBORN,
            source = arrayListOf(header, kept, blocked),
            rules = rules(titleKeywords = setOf("blocked")),
        ) as List<*>

        assertEquals(listOf(header, kept), result)
    }

    private fun rules(
        titleKeywords: Set<String>,
        allowEmptyResult: Boolean = false,
    ) = BookShieldRules(
        authors = emptySet(),
        titleKeywords = titleKeywords,
        fullTitles = emptySet(),
        types = emptySet(),
        typeContains = false,
        wordCountRule = WordCountBlockRule(null, null),
        allowEmptyResult = allowEmptyResult,
        bookIds = emptySet(),
    )

    class TestBook(private val title: String) {
        fun getBookName(): String = title
    }

    class TitledContainer(initialItems: ArrayList<TestBook>) {
        private var storedItems: List<TestBook> = initialItems
        var itemReads = 0
            private set

        val currentItems: List<TestBook>
            get() = storedItems

        fun getName(): String = "Section"

        fun getItems(): List<TestBook> = storedItems.also { itemReads++ }

        fun setItems(value: ArrayList<TestBook>) {
            storedItems = value
        }
    }

    class CyclicContainer {
        fun getRankData(): CyclicContainer = this
    }

    class MorphingData(private val items: List<TestBook>) {
        fun getList(): List<TestBook> = items
    }

    class SanJiangLikeRow(
        @JvmField val BookStoreItem: TestBook?,
    ) {
        fun getBookStoreItem(): TestBook? = BookStoreItem
    }
}
