package com.xiyunmn.qdhelper.feature.bookshelf

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class DailyReadingGameAdCleanerTest {
    @Test
    fun removesTypeOneItemsFromDailyReadingList() {
        val raw = JSONObject()
            .put(
                "Items",
                JSONArray()
                    .put(JSONObject().put("Type", 0).put("BookId", 1001).put("BookName", "Book"))
                    .put(JSONObject().put("Type", 1).put("ADText", "Game").put("Extra", JSONObject()))
                    .put(JSONObject().put("Type", "1")),
            )
            .toString()

        val result = filterDailyReadingGameAds(raw)
        val items = JSONObject(result.json).getJSONArray("Items")

        assertEquals(2, result.removed)
        assertEquals(1, items.length())
        assertEquals(1001L, items.getJSONObject(0).getLong("BookId"))
    }

    @Test
    fun keepsNestedTagTypeValues() {
        val raw = JSONArray()
            .put(
                JSONObject()
                    .put("Type", 0)
                    .put("BookId", 1001)
                    .put("ShowTagList", JSONArray().put(JSONObject().put("Name", "tag").put("Type", 1))),
            )
            .toString()

        val result = filterDailyReadingGameAds(raw)
        val tag = JSONArray(result.json).getJSONObject(0).getJSONArray("ShowTagList").getJSONObject(0)

        assertEquals(0, result.removed)
        assertEquals(1, tag.getInt("Type"))
    }

    @Test
    fun keepsOrdinaryGameCategoryBooks() {
        val raw = JSONObject()
            .put(
                "Items",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("Type", 0)
                            .put("BookId", 1001)
                            .put("BookName", "Book")
                            .put("CategoryName", "游戏")
                            .put("Description", "查看详情"),
                    ),
            )
            .toString()

        val result = filterDailyReadingGameAds(raw)
        val items = JSONObject(result.json).getJSONArray("Items")

        assertEquals(0, result.removed)
        assertEquals(1, items.length())
        assertEquals("游戏", items.getJSONObject(0).getString("CategoryName"))
    }

    @Test
    fun removesNestedExplicitGameAdWithoutDroppingDataObject() {
        val raw = JSONObject()
            .put(
                "Data",
                JSONObject()
                    .put(
                        "Items",
                        JSONArray()
                            .put(JSONObject().put("Type", 0).put("BookId", 1001).put("BookName", "Book")),
                    )
                    .put(
                        "GameAd",
                        JSONObject()
                            .put("Title", "游戏广告")
                            .put("SubTitle", "惊喜活动推荐")
                            .put("ActionUrl", "QDReader://app/game"),
                    ),
            )
            .toString()

        val result = filterDailyReadingGameAds(raw)
        val data = JSONObject(result.json).getJSONObject("Data")

        assertEquals(1, result.removed)
        assertEquals(false, data.has("GameAd"))
        assertEquals(1, data.getJSONArray("Items").length())
    }

    @Test
    fun neutralizesDailyReadingGameAdvertisementEndpoint() {
        val raw = JSONObject()
            .put("Result", 0)
            .put("Data", JSONObject().put("ImageUrl", "https://example.invalid/ad.png"))
            .toString()

        val result = filterDailyReadingGameAds(raw, "/qdgamecenter/GameInfo/getAdvertisement")

        assertEquals(1, result.removed)
        assertEquals("", result.json)
    }

    @Test
    fun keepsMessageCenterFeedEvenWhenItLooksLikeDailyReadingData() {
        val raw = JSONObject()
            .put(
                "Items",
                JSONArray()
                    .put(JSONObject().put("Type", 1).put("BookId", 1001).put("BookName", "Liked book"))
                    .put(JSONObject().put("Type", 1).put("BookId", 1002).put("BookName", "Saved book")),
            )
            .toString()

        val result = filterDailyReadingGameAds(
            raw,
            "/argus/api/v1/message/pullSocialUnreadMessage?pageIndex=1",
        )

        assertEquals(0, result.removed)
        assertEquals(raw, result.json)
    }

    @Test
    fun removesTypeOneItemsFromDailyReadingNetworkList() {
        val raw = JSONObject()
            .put(
                "Items",
                JSONArray()
                    .put(JSONObject().put("Type", 0).put("BookId", 1001).put("BookName", "Book"))
                    .put(JSONObject().put("Type", 1)),
            )
            .toString()

        val result = filterDailyReadingGameAds(
            raw,
            "/ARGUS/API/V2/DAILYRECOMMEND/GETDAILYRECOMMEND/?pageIndex=1",
        )

        assertEquals(1, result.removed)
        assertEquals(1, JSONObject(result.json).getJSONArray("Items").length())
    }

    @Test
    fun invalidJsonReturnsOriginalText() {
        val raw = "{bad json"

        val result = filterDailyReadingGameAds(raw)

        assertEquals(0, result.removed)
        assertEquals(raw, result.json)
    }
}
