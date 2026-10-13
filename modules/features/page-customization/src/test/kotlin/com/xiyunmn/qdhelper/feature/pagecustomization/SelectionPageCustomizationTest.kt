package com.xiyunmn.qdhelper.feature.pagecustomization

import com.qidian.morphing.MorphingCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionPageCustomizationTest {
    @Test
    fun stopsScanningCardsWhenGuessSectionStarts() {
        val items = SelectionPageCustomization.scanItemsFromCards(
            listOf(
                cardWithColumn("今日风向标"),
                cardWithColumn("书友三刷起步"),
                cardWithPageDataType("yuedupianhao"),
                cardWithColumn("多位书友在追，速更！夜不能寐"),
                cardWithColumn("今日热度飙升TOP的吐槽好书"),
            ),
        )

        assertEquals(listOf("今日风向标", "书友三刷起步"), items.map { it.label })
    }

    @Test
    fun disablesOnlyGuessSectionMarkerItself() {
        assertTrue(SelectionPageCustomization.isDisabledItem(PageCustomItem("guess", "猜你喜欢")))
    }

    private fun cardWithColumn(columnName: String): MorphingCard {
        return MorphingCard(
            viewType = 99,
            data = WidgetData(extension = Extension(columnName = columnName)),
        )
    }

    private fun cardWithPageDataType(pageDataType: String): MorphingCard {
        return MorphingCard(
            viewType = 99,
            data = WidgetData(extension = Extension(pageDataType = pageDataType)),
        )
    }

    private data class WidgetData(
        val extension: Extension? = null,
        val selectName: String? = null,
        val listType: String? = null,
        val itemStyle: Int = 0,
    )

    private data class Extension(
        val columnName: String? = null,
        val pageDataType: String? = null,
    )
}
