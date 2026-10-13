package com.xiyunmn.qdhelper.feature.bookshield.hook

import com.xiyunmn.qdhelper.feature.bookshield.policy.BookShieldLocations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookStoreFlutterShieldTest {
    @Test
    fun `daily reading endpoint uses its own location`() {
        assertEquals(
            BookShieldLocations.DAILY_READING_FLUTTER,
            flutterBookShieldLocation("/argus/api/v2/dailyrecommend/getdailyrecommend"),
        )
        assertEquals(
            BookShieldLocations.DAILY_READING_FLUTTER,
            flutterBookShieldLocation(
                "/ARGUS/API/V2/DAILYRECOMMEND/GETDAILYRECOMMEND/?page=1",
            ),
        )
    }

    @Test
    fun `bookstore book endpoint uses the book stream location`() {
        assertEquals(
            BookShieldLocations.FLUTTER_BOOK_STREAM,
            flutterBookShieldLocation("https://fiction.example/argus/api/v1/bookstore/getbooks?page=1"),
        )
        listOf(
            "/argus/api/v1/bookstore/getbooks?page=1",
            "/argus/api/v1/booksquare/getsquarepagepiece",
            "/argus/api/v1/booksquare/getsquarenodepage/",
            "/argus/api/v1/booksquare/getsjrecommend",
            "/argus/api/v1/booksquare/morepagetab",
            "/argus/api/v1/booksquare/newbookmorepage",
            "/argus/api/v1/booksquare/getcolumndiscountbooks",
            "/argus/api/v1/square/morepage",
            "/argus/api/v2/booksquare/newbookpage",
        ).forEach { path ->
            assertEquals(
                path,
                BookShieldLocations.FLUTTER_BOOK_STREAM,
                flutterBookShieldLocation(path),
            )
        }
    }

    @Test
    fun `unknown flutter endpoints are not book shield targets`() {
        assertNull(
            flutterBookShieldLocation("/argus/api/v1/message/pullSocialUnreadMessage"),
        )
        assertNull(flutterBookShieldLocation("/argus/api/v1/search/getbooks"))
        assertNull(flutterBookShieldLocation("/argus/api/v1/booksquare/notinterest"))
    }
}
