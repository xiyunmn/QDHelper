package com.xiyunmn.qdhelper.feature.navigation

import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import com.xiyunmn.qdhelper.config.SettingCollections
import com.xiyunmn.qdhelper.config.SettingIds
import com.xiyunmn.qdhelper.settings.api.SettingsAction
import com.xiyunmn.qdhelper.settings.api.SettingsChoice
import com.xiyunmn.qdhelper.settings.api.SettingsContribution
import com.xiyunmn.qdhelper.settings.api.SettingsContributionId
import com.xiyunmn.qdhelper.settings.api.SettingsPageIds
import com.xiyunmn.qdhelper.settings.api.SettingsRow
import com.xiyunmn.qdhelper.settings.api.SettingsSection

object NavigationSettingsContribution : SettingsContribution {
    override val id = SettingsContributionId("navigation.settings")
    override val owner = OwnerId("navigation")
    override val sections = listOf(
        SettingsSection(
            id = "navigation.bottom-tabs",
            page = SettingsPageIds.NAVIGATION,
            title = "底栏",
            order = 100,
            rows = listOf(
                SettingsRow.Toggle("navigation.red-dot", "隐藏底栏红点角标", "清理底部标签红点、文字角标和运营红点。", SettingIds.KEY_HIDE_BOTTOM_TAB_RED_DOT),
                SettingsRow.Toggle("navigation.account-unread", "隐藏我的Tab未读角标", "拦截我的标签未读红点和气泡。", SettingIds.KEY_HIDE_ACCOUNT_TAB_RED_DOT, searchAliases = listOf(com.xiyunmn.qdhelper.settings.api.SettingsSearchAlias("隐藏我的未读角标", focusTitle = "隐藏我的Tab未读角标"))),
                SettingsRow.Toggle("navigation.activity-tab", "隐藏底栏活动Tab", "隐藏云控插入到底栏第三格的运营/活动标签。", SettingIds.KEY_BOTTOM_TAB_HIDE_ACTIVITY),
                SettingsRow.OrderedChoice(
                    id = "navigation.tab-order",
                    title = "底栏 Tab 显示与排序",
                    summary = "自定义书架、精选、发现、我的显示状态和排列顺序。",
                    enabledKey = SettingIds.KEY_BOTTOM_TAB_CUSTOM_ENABLED,
                    orderKey = SettingIds.KEY_BOTTOM_TAB_ORDER,
                    hiddenKey = SettingIds.KEY_BOTTOM_TAB_HIDDEN,
                    choices = SettingCollections.BOTTOM_TAB_ITEMS.map { SettingsChoice(it.key, it.label) },
                    onChanged = SettingsAction { it.applyUiSettings() },
                ),
            ),
        ),
    )
}
