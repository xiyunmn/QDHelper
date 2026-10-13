package com.xiyunmn.qdhelper.feature.reader

import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import com.xiyunmn.qdhelper.config.SettingCollections
import com.xiyunmn.qdhelper.config.SettingIds
import com.xiyunmn.qdhelper.settings.api.SettingsBooleanChangedAction
import com.xiyunmn.qdhelper.settings.api.SettingsChoice
import com.xiyunmn.qdhelper.settings.api.SettingsContribution
import com.xiyunmn.qdhelper.settings.api.SettingsContributionId
import com.xiyunmn.qdhelper.settings.api.SettingsPageIds
import com.xiyunmn.qdhelper.settings.api.SettingsRow
import com.xiyunmn.qdhelper.settings.api.SettingsSection

object ReaderSettingsContribution : SettingsContribution {
    override val id = SettingsContributionId("reader.settings")
    override val owner = OwnerId("reader")
    override val sections = listOf(
        SettingsSection(
            id = "reader.core-watermark",
            page = SettingsPageIds.CORE,
            title = "阅读",
            order = 200,
            groupId = "core.main",
            rows = listOf(
                SettingsRow.Toggle("reader.watermark", "阅读页去水印", "短路 QDReaderActivity.setWaterMark。", SettingIds.KEY_DISABLE_READER_WATERMARK),
            ),
        ),
        SettingsSection(
            id = "reader.cleanup",
            page = SettingsPageIds.READER,
            title = "阅读页清理",
            order = 100,
            rows = listOf(
                SettingsRow.Toggle("reader.chapter-end", "精简章末推荐", "拦截章末富内容追加，保留正文分页。", SettingIds.KEY_CLEAN_CHAPTER_END),
                SettingsRow.Toggle("reader.last-page", "精简书末页推荐", "隐藏书末页推荐、书友圈、衍生和试读模块。", SettingIds.KEY_CLEAN_LAST_PAGE),
                SettingsRow.Toggle("reader.toolbar-ad", "隐藏阅读页活动浮标", "清空阅读菜单右侧活动浮标。", SettingIds.KEY_HIDE_READER_TOOLBAR_AD),
            ),
        ),
        SettingsSection(
            id = "reader.background",
            page = SettingsPageIds.READER,
            title = "阅读背景",
            order = 250,
            rows = listOf(
                SettingsRow.Toggle(
                    id = "reader.background-local-unlock",
                    title = "本地解锁阅读背景",
                    summary = "允许下载和使用未拥有的会员、活动限定背景。",
                    key = SettingIds.KEY_READER_BACKGROUND_LOCAL_UNLOCK_ENABLED,
                    keywords = setOf("主题", "会员背景", "活动背景", "限定背景"),
                ),
                SettingsRow.Toggle(
                    id = "reader.background-long-press-save",
                    title = "长按背景图保存到相册",
                    summary = "在阅读背景列表或预览页长按图片，保存当前背景图到相册。",
                    key = SettingIds.KEY_READER_BACKGROUND_LONG_PRESS_SAVE_ENABLED,
                    keywords = setOf("主题图片", "下载图片", "保存背景"),
                ),
            ),
        ),
        SettingsSection(
            id = "reader.assistance",
            page = SettingsPageIds.READER,
            title = "阅读辅助",
            order = 200,
            rows = listOf(
                SettingsRow.Toggle(
                    id = "reader.body-copy",
                    title = "正文复制",
                    summary = "在正文长按操作栏中加入复制，点击后直接复制当前选中文字。",
                    key = SettingIds.KEY_READER_BODY_COPY_ENABLED,
                    onChanged = SettingsBooleanChangedAction { scope, _ ->
                        scope.refresh(SettingsPageIds.READER, "段落操作栏排序")
                    },
                    keywords = setOf("段落复制", "阅读正文", "长按正文"),
                ),
                SettingsRow.ParagraphOrder(
                    id = "reader.paragraph-action-order",
                    title = "段落操作栏排序",
                    summary = "调整正文长按操作项的顺序。",
                    orderKey = SettingIds.KEY_PARAGRAPH_ACTION_ORDER,
                    copyEnabledKey = SettingIds.KEY_READER_BODY_COPY_ENABLED,
                    copyChoiceKey = "copy",
                    choices = SettingCollections.PARAGRAPH_ACTION_ITEMS.map { SettingsChoice(it.key, it.label) },
                    normalize = ParagraphActionOrderPolicy::normalize,
                    keywords = setOf("评论", "开始听", "词典", "标记", "段落打赏", "分享", "配音", "纠错"),
                ),
                SettingsRow.Toggle("reader.free-copy", "自由复制", "打开章评完整内容浮窗，可选择复制范围或复制全文。", SettingIds.KEY_FREE_COPY_ENABLED, keywords = setOf("章评", "评论复制", "长按复制")),
                SettingsRow.SingleChoice(
                    id = "reader.free-copy-mode",
                    title = "自由复制触发方式",
                    summary = "两种方式互斥；切换后在章评列表重新出现或重启起点时生效。",
                    key = SettingIds.KEY_FREE_COPY_MODE,
                    defaultKey = SettingIds.FREE_COPY_MODE_LONG_PRESS,
                    choices = listOf(
                        SettingsChoice(SettingIds.FREE_COPY_MODE_LONG_PRESS, "长按直接打开", "长按评论直接打开自由复制浮窗，不显示宿主操作菜单。"),
                        SettingsChoice(SettingIds.FREE_COPY_MODE_MENU_BUTTON, "原生菜单追加复制", "保留踩、举报、分享等原生操作，并在菜单末尾增加复制按钮。"),
                    ),
                    keywords = setOf("复制按钮", "原生菜单"),
                ),
                SettingsRow.Toggle("reader.share-limit", "移除阅读分享次数限制", "放行章评与段落分享策略中的每日次数上限。", SettingIds.KEY_REMOVE_CHAPTER_COMMENT_SHARE_LIMIT, keywords = setOf("章评分享", "段落分享", "分享上限", "每日三次", "每天3次")),
            ),
        ),
    )
}
