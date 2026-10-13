package com.xiyunmn.qdhelper.feature.bookshield.policy

import com.xiyunmn.qdhelper.feature.bookshield.stats.BookShieldStats
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class BookShieldJsonFilterTest {
    @Test
    fun `flutter json uses the same two-sided word rule`() {
        val raw = """
            {
              "items": [
                {"BookId": 1, "BookName": "Short", "WordsCount": 100},
                {"BookId": 2, "BookName": "Middle", "WordsCount": 500},
                {"BookId": 3, "BookName": "Long", "WordsCount": "1000"}
              ]
            }
        """.trimIndent()

        val result = BookShieldJsonFilter.filter(raw, rules("100", "1000"), location = "test")
        val items = JSONObject(result.json).getJSONArray("items")

        assertEquals(2, result.removed)
        assertEquals(1, items.length())
        assertEquals("Middle", items.getJSONObject(0).getString("BookName"))
    }

    @Test
    fun `invalid word range leaves flutter json unchanged`() {
        val raw = """{"items":[{"BookId":1,"BookName":"Book","WordsCount":100}]}"""

        val result = BookShieldJsonFilter.filter(raw, rules("100", "100"), location = "test")

        assertEquals(0, result.removed)
        assertEquals(raw, result.json)
    }

    @Test
    fun `daily reading payload exposes author to shared book rules`() {
        BookShieldStats.clear()
        val raw = """
            {
              "Data": {
                "Items": [{
                  "BookId": 1,
                  "BookName": "Daily Book",
                  "Author": "Blocked Author",
                  "CategoryName": "Fantasy",
                  "Tags": [{"Name":"Magic"}, {"Name":"Adventure"}],
                  "WordsCount": 500
                }]
              }
            }
        """.trimIndent()
        val authorRules = BookShieldRules(
            authors = setOf(BookShieldRules.normalize("Blocked Author")),
            titleKeywords = emptySet(),
            fullTitles = emptySet(),
            types = emptySet(),
            typeContains = false,
            wordCountRule = WordCountBlockRule(null, null),
            allowEmptyResult = true,
            bookIds = emptySet(),
        )

        val result = BookShieldJsonFilter.filter(
            raw,
            authorRules,
            location = BookShieldLocations.DAILY_READING_FLUTTER,
        )

        assertEquals(1, result.removed)
        assertEquals(0, JSONObject(result.json).getJSONObject("Data").getJSONArray("Items").length())
        val event = BookShieldStats.snapshot().events.single()
        assertEquals("Blocked Author", event.author)
        assertEquals(500L, event.words)
        assertEquals("Fantasy", event.category)
        assertEquals("Magic、Adventure", event.tags)
        assertEquals(1L, event.bookId)
    }

    @Test
    fun `flutter secondary list aliases are recognized as book fields`() {
        val raw = """
            {
              "BookItemList": [
                {
                  "CbId": 9,
                  "itemName": "Blocked sub-page book",
                  "authorName": "Author",
                  "bookCategoryName": "Fantasy",
                  "wordCount": 123456
                },
                {"BookId": 10, "bookName": "Kept book"}
              ]
            }
        """.trimIndent()
        val idRules = BookShieldRules(
            authors = emptySet(),
            titleKeywords = emptySet(),
            fullTitles = emptySet(),
            types = emptySet(),
            typeContains = false,
            wordCountRule = WordCountBlockRule(null, null),
            allowEmptyResult = true,
            bookIds = setOf(9L),
        )

        val result = BookShieldJsonFilter.filter(raw, idRules, location = BookShieldLocations.FLUTTER_BOOK_STREAM)
        val items = JSONObject(result.json).getJSONArray("BookItemList")

        assertEquals(1, result.removed)
        assertEquals(1, items.length())
        assertEquals(10L, items.getJSONObject(0).getLong("BookId"))
    }

    private fun rules(atMost: String, atLeast: String) = BookShieldRules(
        authors = emptySet(),
        titleKeywords = emptySet(),
        fullTitles = emptySet(),
        types = emptySet(),
        typeContains = false,
        wordCountRule = WordCountBlockRule.parse(atMost, atLeast),
        allowEmptyResult = false,
        bookIds = emptySet(),
    )
}
