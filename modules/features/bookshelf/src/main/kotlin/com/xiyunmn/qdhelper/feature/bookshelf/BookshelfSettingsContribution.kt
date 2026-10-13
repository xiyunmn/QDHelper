package com.xiyunmn.qdhelper.feature.bookshelf

import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import com.xiyunmn.qdhelper.config.SettingCollections
import com.xiyunmn.qdhelper.config.SettingIds
import com.xiyunmn.qdhelper.settings.api.SettingsAction
import com.xiyunmn.qdhelper.settings.api.SettingsChoice
import com.xiyunmn.qdhelper.settings.api.SettingsContribution
import com.xiyunmn.qdhelper.settings.api.SettingsContributionId
import com.xiyunmn.qdhelper.settings.api.SettingsPageIds
import com.xiyunmn.qdhelper.settings.api.SettingsRow
import com.xiyunmn.qdhelper.settings.api.SettingsSearchAlias
import com.xiyunmn.qdhelper.settings.api.SettingsSection

object BookshelfSettingsContribution : SettingsContribution {
    override val id = SettingsContributionId("bookshelf.settings")
    override val owner = OwnerId("bookshelf")
    override val sections = listOf(
        SettingsSection(
            id = "bookshelf.home",
            page = SettingsPageIds.BOOKSHELF,
            title = "书架与首页",
            order = 100,
            rows = listOf(
                SettingsRow.Toggle("bookshelf.day-palette", "夜间书架沿用日间配色", "将书架 DarkMode 配置替换为 LightMode。", SettingIds.KEY_BOOKSHELF_NIGHT_MODE_SAME_AS_DAY),
                SettingsRow.Toggle("bookshelf.hover-ad", "隐藏书架悬浮广告", "短路书架悬浮广告选择回调。", SettingIds.KEY_HIDE_BOOKSHELF_HOVER_AD),
                SettingsRow.Toggle("bookshelf.top-activity-ad", "隐藏书架顶部活动广告", "隐藏顶部运营活动横幅、背景和活动入口。", SettingIds.KEY_HIDE_BOOKSHELF_TOP_ACTIVITY_AD),
                SettingsRow.Toggle("bookshelf.left-top-activity", "隐藏书架左上角活动入口", "隐藏云控的活动入口，恢复阅读时长区域。", SettingIds.KEY_HIDE_BOOKSHELF_LEFT_TOP_ACTIVITY_CARD),
                SettingsRow.Toggle("bookshelf.daily-reading-game-ad", "移除每日导读游戏广告", "过滤每日导读 Flutter 页返回数据中的游戏广告卡片。", SettingIds.KEY_HIDE_DAILY_READING_GAME_AD),
                SettingsRow.Toggle("bookshelf.book-activity-tag", "隐藏书籍活动标签", "隐藏书架书籍条目的活动气泡和运营标签。", SettingIds.KEY_HIDE_BOOKSHELF_BOOK_ACTIVITY_TAG),
                SettingsRow.Toggle("bookshelf.update-notice", "隐藏书籍更新提示", "隐藏书架条目更新红点和更新提醒小卡。", SettingIds.KEY_HIDE_BOOKSHELF_UPDATE_NOTICE),
                SettingsRow.Toggle("bookshelf.reading-time-card", "隐藏书架顶部入口区", "隐藏书架顶部阅读时长、签到和每日导读入口所在区域。", SettingIds.KEY_HIDE_BOOKSHELF_READING_TIME_CARD),
                SettingsRow.OrderedChoice(
                    id = "bookshelf.home-top-actions",
                    title = "右上角控件",
                    summary = "自定义搜索、游戏、更多三个入口的显示和顺序。",
                    enabledKey = SettingIds.KEY_HOME_TOP_ACTION_CUSTOM_ENABLED,
                    orderKey = SettingIds.KEY_HOME_TOP_ACTION_ORDER,
                    hiddenKey = SettingIds.KEY_HOME_TOP_ACTION_HIDDEN,
                    choices = SettingCollections.HOME_TOP_ACTION_ITEMS.map { SettingsChoice(it.key, it.label) },
                    onChanged = SettingsAction { it.applyUiSettings() },
                    searchAliases = listOf(SettingsSearchAlias("首页右上角控件", focusTitle = "右上角控件")),
                ),
            ),
        ),
    )
}
