package com.xiyunmn.qdhelper.feature.bookshield

import com.xiyunmn.qdhelper.feature.bookshield.ui.BookShieldWordRangeText
import com.xiyunmn.qdhelper.feature.bookshield.settings.BookShieldDocumentController
import com.xiyunmn.qdhelper.feature.bookshield.settings.BookShieldSessionInputs
import com.xiyunmn.qdhelper.config.BookShieldRuleField
import com.xiyunmn.qdhelper.config.BookShieldRuleConfig
import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import com.xiyunmn.qdhelper.config.SettingCollections
import com.xiyunmn.qdhelper.config.SettingIds
import com.xiyunmn.qdhelper.settings.api.SettingsChoice
import com.xiyunmn.qdhelper.settings.api.SettingsContribution
import com.xiyunmn.qdhelper.settings.api.SettingsContributionId
import com.xiyunmn.qdhelper.settings.api.SettingsPageIds
import com.xiyunmn.qdhelper.settings.api.SettingsRow
import com.xiyunmn.qdhelper.settings.api.SettingsSection

object BookShieldSettingsContribution : SettingsContribution {
    override val id = SettingsContributionId("book-shield.settings")
    override val owner = OwnerId("book-shield")
    override val sections = listOf(
        SettingsSection(
            id = "book-shield.activation",
            page = SettingsPageIds.BOOK_SHIELD,
            title = "书籍屏蔽",
            order = 100,
            rows = listOf(
                SettingsRow.Toggle("book-shield.enabled", "启用书籍屏蔽", "按作者、书名、分类和字数过滤推荐流、排行、漫画和书末页数据。", SettingIds.KEY_BOOK_SHIELD_ENABLED),
                SettingsRow.Toggle("book-shield.quick-add", "详情页快速屏蔽", "仅在书籍详情页长按书名或作者时弹出确认。", SettingIds.KEY_BOOK_SHIELD_QUICK_ADD),
                SettingsRow.MultiChoice(
                    id = "book-shield.locations",
                    title = "生效位置",
                    summary = "同类精选/分类标签和二级栏目按原生、Flutter 两组控制；关闭后对应数据入口直接放行。",
                    key = SettingIds.KEY_BOOK_SHIELD_LOCATIONS,
                    choices = SettingCollections.BOOK_SHIELD_LOCATION_ITEMS.map { SettingsChoice(it.key, it.label) },
                    defaultSelected = SettingCollections.DEFAULT_BOOK_SHIELD_LOCATIONS,
                ),
            ),
        ),
        SettingsSection(
            id = "book-shield.rules",
            page = SettingsPageIds.BOOK_SHIELD,
            title = "规则",
            order = 200,
            rows = listOf(
                SettingsRow.PageLink(
                    id = "book-shield.file-editor-link",
                    title = "配置文件编辑",
                    summary = "使用语法强调色编辑完整 YAML，或直接交给外部文件编辑器。",
                    targetPage = SettingsPageIds.BOOK_SHIELD_CONFIG,
                    keywords = setOf("YAML", "规则文件", "外部编辑器", "语法高亮"),
                ),
                sessionInput(
                    id = "book-shield.authors",
                    title = "作者名",
                    summary = "每行一个作者名。本次运行输入的作者会合并进 YAML；删除时只移除本次新增的规则。",
                    field = BookShieldRuleField.AUTHORS,
                    hint = "每行一个作者名",
                ),
                sessionInput(
                    id = "book-shield.name-keywords",
                    title = "书名关键词",
                    summary = "每行一个关键词。本次运行输入的关键词会合并进 YAML；书名包含任一关键词即过滤。",
                    field = BookShieldRuleField.TITLE_KEYWORDS,
                    hint = "每行一个关键词",
                ),
                sessionInput(
                    id = "book-shield.full-names",
                    title = "完整书名",
                    summary = "每行一个完整书名，书名中的逗号、空格按原样保留。本次运行输入的书名会合并进 YAML。",
                    field = BookShieldRuleField.FULL_TITLES,
                    hint = "每行一个完整书名",
                ),
                sessionInput(
                    id = "book-shield.types",
                    title = "分类或类型",
                    summary = "每行一个分类。本次运行输入的分类会合并进 YAML，支持分类、子分类、标签、类型码文本。",
                    field = BookShieldRuleField.TYPES,
                    hint = "每行一个分类或类型",
                ),
                sessionInput(
                    id = "book-shield.book-ids",
                    title = "屏蔽 book_id",
                    summary = "本次运行输入的正整数书号会合并进 YAML；空格、逗号或换行分隔。详情页长按可快速加入。",
                    field = BookShieldRuleField.BOOK_IDS,
                    hint = "1000001 1000002",
                    keywords = setOf("book_id", "bookid", "书籍id", "按id屏蔽"),
                ),
                SettingsRow.Toggle("book-shield.type-contains", "类型增强匹配", "开启后分类或类型包含关键词即可过滤；关闭时要求完整匹配。", SettingIds.KEY_BOOK_SHIELD_TYPE_CONTAINS),
                SettingsRow.Action(
                    id = "book-shield.word-range",
                    tool = com.xiyunmn.qdhelper.feature.bookshield.settings.BookShieldWordRangeTool,
                    title = "字数屏蔽范围",
                    summary = "分别设置字数过少和字数过多的屏蔽阈值。",
                    actionText = "设置",
                    summaryProvider = { BookShieldWordRangeText.summary(it) },
                    keywords = setOf("字数过少", "字数过多", "字数范围", "最少字数", "最多字数"),
                ),
                SettingsRow.Toggle("book-shield.allow-empty", "允许过滤为空", "默认保留最后一批原数据以降低宿主空列表崩溃风险。", SettingIds.KEY_BOOK_SHIELD_ALLOW_EMPTY_RESULT),
                SettingsRow.Action(
                    id = "book-shield.stats",
                    tool = com.xiyunmn.qdhelper.feature.bookshield.settings.BookShieldStatsTool,
                    title = "查看最近统计",
                    summary = "查看本次运行屏蔽的每本书、命中原因、所在页面与数量（重启宿主后清零）。",
                    actionText = "查看",
                ),
            ),
            resetActions = mapOf(
                "book-shield.rules-file-reset" to com.xiyunmn.qdhelper.settings.api.SettingsResetAction { context ->
                    BookShieldDocumentController.reset(context)
                },
            ),
        ),
        SettingsSection(
            id = "book-shield.file-editor",
            page = SettingsPageIds.BOOK_SHIELD_CONFIG,
            title = "书籍屏蔽 YAML",
            order = 100,
            rows = listOf(
                SettingsRow.DocumentEditor(
                    id = "book-shield.document-editor",
                    title = "规则文件",
                    summary = "注释、键、字符串和数值使用 YAML 强调色；保存或外部编辑返回时会校验语法。",
                    controller = BookShieldDocumentController,
                    keywords = setOf("YAML", "规则文件", "文件编辑"),
                ),
            ),
        ),
        SettingsSection(
            id = "book-shield.diagnostics",
            page = SettingsPageIds.DIAGNOSTICS,
            title = "调试",
            order = 200,
            groupId = "diagnostics.debug",
            rows = listOf(
                SettingsRow.Toggle(
                    id = "book-shield.trace",
                    title = "书籍屏蔽诊断日志",
                    summary = "开启后按过滤批次汇总条目类型、脱敏样本、命中数量与耗时并写入文件日志。",
                    key = SettingIds.KEY_DEBUG_SHIELD_TRACE_ENABLED,
                    keywords = setOf("屏蔽诊断", "shield trace", "屏蔽日志"),
                ),
            ),
        ),
    )

    private fun sessionInput(
        id: String,
        title: String,
        summary: String,
        field: BookShieldRuleField,
        hint: String,
        keywords: Set<String> = emptySet(),
    ): SettingsRow.SessionTextInput = SettingsRow.SessionTextInput(
        id = id,
        title = title,
        summary = summary,
        valueProvider = com.xiyunmn.qdhelper.settings.api.SettingsTextProvider { context ->
            BookShieldSessionInputs.value(context, field)
        },
        hint = hint,
        singleLine = false,
        onChanged = com.xiyunmn.qdhelper.settings.api.SettingsTextChangedAction { scope, value ->
            BookShieldSessionInputs.update(scope.activity, field, value)
        },
        validator = if (field == BookShieldRuleField.BOOK_IDS) {
            { value ->
                BookShieldRuleConfig.splitQuickInput(value).all { token ->
                    token.toLongOrNull()?.let { id -> id > 0L } == true
                }
            }
        } else null,
        keywords = keywords,
    )
}
