package com.xiyunmn.qdhelper.feature.bookshield.hook

import com.xiyunmn.qdhelper.feature.bookshield.policy.BookShieldLocations
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BookShieldLastPageFeatureTest {
    @Test
    fun `last page morphing adapters are recognized without claiming ordinary morphing`() {
        assertEquals(true, isLastPageMorphingAdapterClassName(HostSymbols.Classes.LAST_PAGE_ADAPTER))
        assertEquals(true, isLastPageMorphingAdapterClassName(HostSymbols.Classes.LAST_PAGE_V4_ADAPTER))
        assertEquals(false, isLastPageMorphingAdapterClassName(HostSymbols.Classes.MORPHING_ADAPTER))
        assertEquals(listOf("updateList", "updateListNoDiff", "appendList", "addItems"), lastPageMorphingMethodNames)
    }

    @Test
    fun `morphing install is deferred until after package ready`() {
        assertEquals(false, isLastPageMorphingInstallPhase("packageReady"))
        assertEquals(true, isLastPageMorphingInstallPhase("applicationReady:QDApplication"))
        assertEquals(true, isLastPageMorphingInstallPhase("hotReloaded"))
    }

    @Test
    fun `all last page sources retain their location mapping`() {
        assertEquals(
            listOf(
                LastPageBookShieldHook(
                    HostSymbols.Classes.BOOK_LAST_PAGE,
                    "getAlsoReadList",
                    BookShieldLocations.READ_LAST_PAGE_SIMILAR_BOOK,
                ),
                LastPageBookShieldHook(
                    HostSymbols.Classes.BOOK_LAST_PAGE,
                    "getRecommendList",
                    BookShieldLocations.READ_LAST_PAGE_RECOMMEND,
                ),
                LastPageBookShieldHook(
                    HostSymbols.Classes.BOOK_LAST_PAGE,
                    "getRelatedBookList",
                    BookShieldLocations.READ_LAST_PAGE_RECOMMEND_BY_TAG,
                ),
                LastPageBookShieldHook(
                    HostSymbols.Classes.BOOK_LAST_PAGE,
                    "getTagRecommendList",
                    BookShieldLocations.READ_LAST_PAGE_RECOMMEND_BY_TAG,
                ),
                LastPageBookShieldHook(
                    HostSymbols.Classes.BOOK_LAST_PAGE,
                    "getSimilarRecommend",
                    BookShieldLocations.READ_LAST_PAGE_SIMILAR_BOOK,
                    LastPageBookShieldSourceShape.CONTAINER,
                ),
            ),
            lastPageBookShieldHooks,
        )
        assertEquals(5, lastPageBookShieldHooks.size)
    }

    @Test
    fun `last page morphing cards resolve to their actual sections`() {
        assertEquals(
            BookShieldLocations.READ_LAST_PAGE_SIMILAR_BOOK,
            lastPageMorphingLocation(
                LastPageMorphingCardDescriptor(columnName = "kanguocishuderenhaikanguo"),
            ),
        )
        assertEquals(
            BookShieldLocations.READ_LAST_PAGE_READ_MORE,
            lastPageMorphingLocation(LastPageMorphingCardDescriptor(columnName = "yuedupianhao")),
        )
        assertEquals(
            BookShieldLocations.READ_LAST_PAGE_READ_MORE,
            lastPageMorphingLocation(LastPageMorphingCardDescriptor(columnName = "tuijianliyou")),
        )
        assertEquals(
            BookShieldLocations.READ_LAST_PAGE_RECOMMEND_BY_TAG,
            lastPageMorphingLocation(
                LastPageMorphingCardDescriptor(viewType = 14, trackViewType = 107, multiDataSize = 5),
            ),
        )
        assertEquals(
            null,
            lastPageMorphingLocation(LastPageMorphingCardDescriptor(columnName = "unknown")),
        )
        assertEquals(
            null,
            lastPageMorphingLocation(
                LastPageMorphingCardDescriptor(viewType = 14, trackViewType = 107, multiDataSize = 0),
            ),
        )
    }

    @Test
    fun `routed cards honor independent location switches and leave unknown cards untouched`() {
        data class Card(val location: String?)

        val similar = Card(BookShieldLocations.READ_LAST_PAGE_SIMILAR_BOOK)
        val tags = Card(BookShieldLocations.READ_LAST_PAGE_RECOMMEND_BY_TAG)
        val more = Card(BookShieldLocations.READ_LAST_PAGE_READ_MORE)
        val unknown = Card(null)
        val visited = mutableListOf<String>()

        val result = transformRoutedLastPageCards(
            cards = listOf(similar, tags, more, unknown),
            resolveLocation = Card::location,
            isLocationEnabled = { it != BookShieldLocations.READ_LAST_PAGE_RECOMMEND_BY_TAG },
            transform = { location, card ->
                visited += location
                card.copy()
            },
        )

        assertEquals(
            listOf(
                BookShieldLocations.READ_LAST_PAGE_SIMILAR_BOOK,
                BookShieldLocations.READ_LAST_PAGE_READ_MORE,
            ),
            visited,
        )
        assertSame(tags, result[1])
        assertSame(unknown, result[3])
    }

    @Test
    fun `routed transform can remove an entire recommendation card`() {
        data class Card(val id: Int, val location: String?)

        val result = transformRoutedLastPageCards(
            cards = listOf(
                Card(1, BookShieldLocations.READ_LAST_PAGE_READ_MORE),
                Card(2, BookShieldLocations.READ_LAST_PAGE_READ_MORE),
                Card(3, null),
            ),
            resolveLocation = Card::location,
            isLocationEnabled = { true },
            transform = { _, card -> if (card.id == 1) null else card },
        )

        assertEquals(listOf(2, 3), result.map(Card::id))
    }

    @Test
    fun `only emptied more-reading book cards remove their recommendation text`() {
        val recommendation = LastPageMorphingCardDescriptor(columnName = "tuijianliyou")
        val sectionHeader = LastPageMorphingCardDescriptor(columnName = "yuedupianhao")

        assertEquals(true, shouldRemoveEmptyReadMoreCard(recommendation, 1, 0))
        assertEquals(false, shouldRemoveEmptyReadMoreCard(recommendation, 1, 1))
        assertEquals(false, shouldRemoveEmptyReadMoreCard(sectionHeader, null, null))
    }

    @Test
    fun `ordinary morphing locations require selection or a known book host`() {
        assertEquals(
            BookShieldLocations.SELECTION_MORPHING,
            morphingBookShieldLocation(isFromSelection = true),
        )
        assertEquals(
            BookShieldLocations.BOOKSTORE_REBORN,
            morphingBookShieldLocation(
                isFromSelection = false,
                contextClassName = "com.qidian.QDReader.ui.modules.bookstore.MorphingActivity",
            ),
        )
        assertEquals(
            BookShieldLocations.BOOKSTORE_REBORN,
            morphingBookShieldLocation(isFromSelection = false, isActivity = true),
        )
        assertEquals(
            null,
            morphingBookShieldLocation(
                isFromSelection = false,
                contextClassName = "com.qidian.QDReader.ui.activity.QDSearchActivity",
            ),
        )
    }

    @Test
    fun `generic book item adapter is limited to the actual book list activity`() {
        assertEquals(
            true,
            isBookListActivityBookItemAdapterContext(HostSymbols.Classes.BOOK_LIST_ACTIVITY),
        )
        assertEquals(
            false,
            isBookListActivityBookItemAdapterContext("com.qidian.QDReader.ui.activity.QDSearchActivity"),
        )
        assertEquals(false, isBookListActivityBookItemAdapterContext(null))
    }
}
