package com.xiyunmn.qdhelper.config

object SettingCollections {
    val BOTTOM_TAB_ITEMS = listOf(
        ChoiceItem("shelf", "书架"),
        ChoiceItem("selection", "精选"),
        ChoiceItem("discover", "发现"),
        ChoiceItem("mine", "我"),
    )
    val HOME_TOP_ACTION_ITEMS = listOf(
        ChoiceItem("search", "搜索"),
        ChoiceItem("game", "游戏"),
        ChoiceItem("more", "更多"),
    )
    val PARAGRAPH_ACTION_ITEMS = listOf(
        ChoiceItem("comment", "评论"),
        ChoiceItem("listen", "开始听"),
        ChoiceItem("dictionary", "词典"),
        ChoiceItem("mark", "标记"),
        ChoiceItem("reward", "段落打赏"),
        ChoiceItem("share", "分享"),
        ChoiceItem("dubbing", "配音"),
        ChoiceItem("correction", "纠错"),
        ChoiceItem("copy", "复制"),
    )
    val BOOK_SHIELD_LOCATION_ITEMS = listOf(
        ChoiceItem("selection_morphing", "精选主页面（原生）"),
        ChoiceItem("flutter_book_stream", "精选/分类及二级页（Flutter）"),
        ChoiceItem("bookstore_reborn", "精选/分类二级页（原生）"),
        ChoiceItem("category_all", "分类全部"),
        ChoiceItem("category_recommend", "分类推荐"),
        ChoiceItem("free_recommend", "免费推荐"),
        ChoiceItem("hot_recommend", "热门推荐"),
        ChoiceItem("ranking", "排行榜"),
        ChoiceItem("comic", "漫画广场"),
        ChoiceItem("daily_reading_flutter", "新每日导读（Flutter）"),
        ChoiceItem("read_last_page_read_more", "书末页更多阅读"),
        ChoiceItem("read_last_page_similar_book", "书末页相似书籍"),
        ChoiceItem("read_last_page_recommend", "书末页推荐"),
        ChoiceItem("read_last_page_recommend_by_tag", "书末页标签推荐"),
    )

    val DEFAULT_BOOK_SHIELD_LOCATIONS = linkedSetOf(
        "selection_morphing",
        "flutter_book_stream",
        "daily_reading_flutter",
        "bookstore_reborn",
        "ranking",
        "read_last_page_read_more",
        "read_last_page_similar_book",
        "read_last_page_recommend",
        "read_last_page_recommend_by_tag",
    )

    val SPLASH_SOURCE_TOGGLE_KEYS = listOf(
        SettingIds.KEY_LOCAL_SPLASH_IMAGE_ENABLED,
        SettingIds.KEY_CUSTOM_SPLASH_IMAGE_ENABLED,
        SettingIds.KEY_OFFICIAL_SPLASH_IMAGE_ENABLED,
    )
}

data class ChoiceItem(val key: String, val label: String)
