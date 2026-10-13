package com.xiyunmn.qdhelper.feature.readerfont

import com.xiyunmn.qdhelper.config.SettingIds
import com.xiyunmn.qdhelper.feature.readerfont.settings.FontRoleChoiceController
import com.xiyunmn.qdhelper.feature.readerfont.settings.ReaderFontManagedListController
import com.xiyunmn.qdhelper.feature.readerfont.ui.ReaderFontSettingsActions
import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import com.xiyunmn.qdhelper.settings.api.SettingsAction
import com.xiyunmn.qdhelper.settings.api.SettingsContribution
import com.xiyunmn.qdhelper.settings.api.SettingsContributionId
import com.xiyunmn.qdhelper.settings.api.SettingsPageIds
import com.xiyunmn.qdhelper.settings.api.SettingsRow
import com.xiyunmn.qdhelper.settings.api.SettingsSearchAlias
import com.xiyunmn.qdhelper.settings.api.SettingsSection
import com.xiyunmn.qdhelper.shared.fonts.FontRole

object ReaderFontSettingsContribution : SettingsContribution {
    override val id = SettingsContributionId("reader-font.settings")
    override val owner = OwnerId("reader-font")
    private val fontSettingKeys = setOf(
        SettingIds.KEY_CUSTOM_READER_FONT_ENABLED,
        SettingIds.KEY_CUSTOM_READER_FONT_LIST,
        SettingIds.KEY_CUSTOM_READER_FONT_PATH,
        SettingIds.KEY_CUSTOM_READER_FONT_NAME,
        SettingIds.KEY_CUSTOM_GLOBAL_FONT_ENABLED,
        SettingIds.KEY_CUSTOM_GLOBAL_FONT_REGULAR_PATH,
        SettingIds.KEY_CUSTOM_GLOBAL_FONT_REGULAR_NAME,
        SettingIds.KEY_CUSTOM_GLOBAL_FONT_EMPHASIS_PATH,
        SettingIds.KEY_CUSTOM_GLOBAL_FONT_EMPHASIS_NAME,
    )
    private val readerChoice = FontRoleChoiceController(FontRole.READER)
    private val globalRegularChoice = FontRoleChoiceController(FontRole.GLOBAL_REGULAR)
    private val globalEmphasisChoice = FontRoleChoiceController(FontRole.GLOBAL_EMPHASIS)

    override val sections = listOf(
        SettingsSection(
            id = "reader-font.custom",
            page = SettingsPageIds.READER,
            title = "自定义阅读字体",
            order = 300,
            rows = listOf(
                SettingsRow.Toggle(
                    id = "reader-font.enabled",
                    title = "自定义阅读字体",
                    summary = "导入 .ttf / .otf / .ttc 字体后替换阅读正文 Typeface；关闭后恢复起点原生字体。",
                    key = SettingIds.KEY_CUSTOM_READER_FONT_ENABLED,
                ),
                SettingsRow.DynamicChoice(
                    id = "reader-font.selection",
                    title = "阅读字体",
                    summary = "从共享字体库中选择阅读正文使用的字体。",
                    defaultChoiceTitle = "使用宿主阅读字体",
                    emptyText = "暂无已导入字体",
                    controller = readerChoice,
                    settingKeys = setOf(
                        SettingIds.KEY_CUSTOM_READER_FONT_ENABLED,
                        SettingIds.KEY_CUSTOM_READER_FONT_PATH,
                        SettingIds.KEY_CUSTOM_READER_FONT_NAME,
                    ),
                ),
                SettingsRow.PageLink(
                    id = "reader-font.manager-link",
                    title = "管理共享字体库",
                    summary = "阅读字体与界面字体共用同一套本地字体文件。",
                    targetPage = SettingsPageIds.READER_FONT_MANAGER,
                    keywords = setOf("字体导入", "字体排序", "删除字体"),
                    searchAliases = listOf(
                        SettingsSearchAlias("导入自定义字体", focusTitle = "管理共享字体库"),
                        SettingsSearchAlias("清除自定义字体", focusTitle = "管理共享字体库"),
                    ),
                ),
            ),
            resetKeys = setOf(
                SettingIds.KEY_CUSTOM_READER_FONT_PATH,
                SettingIds.KEY_CUSTOM_READER_FONT_NAME,
            ),
        ),
        SettingsSection(
            id = "reader-font.global",
            page = SettingsPageIds.CORE,
            title = "界面字体",
            order = 300,
            rows = listOf(
                SettingsRow.Toggle(
                    id = "reader-font.global-enabled",
                    title = "自定义全局字体",
                    summary = "替换宿主原生及 Flutter 界面的常规文字，不替换阅读正文和特殊装饰字体。",
                    key = SettingIds.KEY_CUSTOM_GLOBAL_FONT_ENABLED,
                ),
                SettingsRow.DynamicChoice(
                    id = "reader-font.global-regular",
                    title = "界面常规字体",
                    summary = "用于正文、说明、输入框等常规字重。",
                    defaultChoiceTitle = "不替换界面常规字体",
                    emptyText = "暂无已导入字体",
                    controller = globalRegularChoice,
                    settingKeys = setOf(
                        SettingIds.KEY_CUSTOM_GLOBAL_FONT_ENABLED,
                        SettingIds.KEY_CUSTOM_GLOBAL_FONT_REGULAR_PATH,
                        SettingIds.KEY_CUSTOM_GLOBAL_FONT_REGULAR_NAME,
                    ),
                ),
                SettingsRow.DynamicChoice(
                    id = "reader-font.global-emphasis",
                    title = "界面强调字体",
                    summary = "用于标题、按钮、粗体及 Flutter 强调字族；未单独选择时跟随常规字体。",
                    defaultChoiceTitle = "跟随界面常规字体",
                    emptyText = "暂无已导入字体",
                    controller = globalEmphasisChoice,
                    settingKeys = setOf(
                        SettingIds.KEY_CUSTOM_GLOBAL_FONT_ENABLED,
                        SettingIds.KEY_CUSTOM_GLOBAL_FONT_EMPHASIS_PATH,
                        SettingIds.KEY_CUSTOM_GLOBAL_FONT_EMPHASIS_NAME,
                    ),
                ),
                SettingsRow.PageLink(
                    id = "reader-font.global-manager-link",
                    title = "管理共享字体库",
                    summary = "导入、排序或删除阅读页与其他界面共用的字体。",
                    targetPage = SettingsPageIds.READER_FONT_MANAGER,
                    keywords = setOf("全局字体", "界面字体", "字体导入"),
                ),
            ),
            resetKeys = setOf(
                SettingIds.KEY_CUSTOM_GLOBAL_FONT_REGULAR_PATH,
                SettingIds.KEY_CUSTOM_GLOBAL_FONT_REGULAR_NAME,
                SettingIds.KEY_CUSTOM_GLOBAL_FONT_EMPHASIS_PATH,
                SettingIds.KEY_CUSTOM_GLOBAL_FONT_EMPHASIS_NAME,
            ),
        ),
        SettingsSection(
            id = "reader-font.manager",
            page = SettingsPageIds.READER_FONT_MANAGER,
            title = "共享字体库",
            order = 100,
            rows = listOf(
                SettingsRow.Action(
                    id = "reader-font.manager-import",
                    title = "导入字体",
                    summary = "从文件选择器导入字体文件，支持多选；导入后返回对应选项选择使用。",
                    actionText = "导入",
                    action = SettingsAction { ReaderFontSettingsActions.openImporter(it.activity) },
                    settingKeys = fontSettingKeys,
                ),
                SettingsRow.ManagedList(
                    id = "reader-font.manager-list",
                    title = "已导入字体",
                    summary = "拖动左侧把手排序；阅读、常规和强调字体引用会分别标记。",
                    emptyText = "暂无已导入字体，请先导入字体文件",
                    controller = ReaderFontManagedListController,
                    settingKeys = fontSettingKeys,
                ),
                SettingsRow.Action(
                    id = "reader-font.manager-clear",
                    title = "清除全部字体",
                    summary = "清空共享字体库，并关闭阅读字体和界面字体。",
                    actionText = "清除",
                    action = SettingsAction { ReaderFontSettingsActions.clear(it.activity) },
                    settingKeys = fontSettingKeys,
                ),
            ),
        ),
    )
}
