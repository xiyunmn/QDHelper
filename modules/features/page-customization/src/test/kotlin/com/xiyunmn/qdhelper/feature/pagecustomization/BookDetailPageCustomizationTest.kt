package com.xiyunmn.qdhelper.feature.pagecustomization

import org.junit.Assert.assertEquals
import org.junit.Test

class BookDetailPageCustomizationTest {
    @Test
    fun exposesFixedBookDetailItemsInDisplayOrder() {
        val items = BookDetailPageCustomization.knownItems

        assertEquals(
            listOf(
                BookDetailPageCustomization.ITEM_CIRCLE,
                BookDetailPageCustomization.ITEM_AUTHOR,
                BookDetailPageCustomization.ITEM_FANS_RANK,
                BookDetailPageCustomization.ITEM_MONTH_TICKET_SPONSOR,
                BookDetailPageCustomization.ITEM_LOOK_FOR,
                BookDetailPageCustomization.ITEM_ACTIVITY_BANNER,
                BookDetailPageCustomization.ITEM_ACTIVITY_FLOAT,
                BookDetailPageCustomization.ITEM_SAME_RECOMMEND,
                BookDetailPageCustomization.ITEM_OTHER_READ,
                BookDetailPageCustomization.ITEM_COPYRIGHT,
            ),
            items.map { it.key },
        )
        assertEquals(
            listOf(
                "书友圈",
                "作家相关",
                "书友榜",
                "月票金主",
                "本书看点",
                "活动广告",
                "活动浮标",
                "同类作品推荐",
                "看过此书的人还推荐",
                "版权改编",
            ),
            items.map { it.label },
        )
    }
}
