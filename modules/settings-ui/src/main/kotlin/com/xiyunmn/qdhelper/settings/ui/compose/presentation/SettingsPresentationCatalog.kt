package com.xiyunmn.qdhelper.settings.ui.compose.presentation

import com.xiyunmn.qdhelper.settings.api.SettingsContribution
import com.xiyunmn.qdhelper.settings.api.SettingsPageId
import com.xiyunmn.qdhelper.settings.api.SettingsRow
import com.xiyunmn.qdhelper.settings.api.SettingsSection
import com.xiyunmn.qdhelper.settings.api.SettingsPageIds

internal data class SettingsDestination(
    val id: String, val title: String, val parent: String?, val summary: String = "",
    val rowIds: List<String> = emptyList(),
)

internal enum class SettingRisk(val label: String) { Normal(""), Attention("注意"), HighRisk("高风险"), Experimental("实验"), Diagnostic("诊断") }
internal data class SettingDependency(val rowId: String, val reason: String)
internal data class SettingsUiMeta(
    val destination: String,
    val risk: SettingRisk = SettingRisk.Normal,
    val dependencies: List<SettingDependency> = emptyList(),
)

/** Explicit presentation coverage: adding a business row requires a deliberate user-task placement. */
internal class SettingsPresentationCatalog(contributions: List<SettingsContribution>) {
    val sections = contributions.flatMap { it.sections }
    val rows = sections.flatMap { it.rows }.associateBy { it.id }
    val sourceSection = sections.flatMap { section -> section.rows.map { it.id to section } }.toMap()
    val destinations = buildList {
        fun page(id: String, title: String, parent: String?, summary: String = "", ids: String = "") {
            add(SettingsDestination(id, title, parent, summary, ids.split(' ').filter(String::isNotBlank)))
        }
        page("home", "QDHelper", null, ids = "core.module-enabled")
        page("reading", "阅读体验", "home", "正文 · 复制 · 外观 · 字体")
        page("interface", "界面定制", "home", "开屏 · 书架 · 导航 · 页面")
        page("content", "内容工具", "home", "屏蔽 · 图片 · 卡牌 · 导出")
        page("account", "账号与自动化", "home", "签到 · 投资 · 时长 · 福利")
        page("privacy", "弹窗与上报", "home", "弹窗 · DNS · 上报")
        page("tools", "工具与诊断", "home", "解析 · 指纹 · 日志 · 配置")
        page("reader-clean", "阅读界面", "reading", ids = "reader.watermark reader.chapter-end reader.last-page reader.toolbar-ad")
        page("copy", "复制与分享", "reading", ids = "reader.body-copy reader.paragraph-action-order reader.free-copy reader.free-copy-mode reader.share-limit")
        page("background", "阅读外观", "reading", ids = "reader.background-local-unlock reader.background-long-press-save")
        page("fonts", "字体", "reading", "阅读正文与其他界面使用独立角色", "reader-font.enabled reader-font.selection reader-font.manager-link reader-font.global-enabled reader-font.global-regular reader-font.global-emphasis reader-font.global-manager-link")
        page("font-library", "共享字体库", "fonts", "阅读正文与其他界面共用", "reader-font.manager-import reader-font.manager-list reader-font.manager-clear")
        page("splash", "启动与开屏", "interface", ids = "startup.block-ad startup.skip-image startup.dark-logo startup.sync startup.local-enabled startup.local-pick startup.local-clear startup.custom-enabled startup.custom-url startup.official-local-unlock startup.official-enabled startup.official-url startup.official-paper")
        page("bookshelf", "书架与首页", "interface", ids = "bookshelf.day-palette bookshelf.hover-ad bookshelf.top-activity-ad bookshelf.left-top-activity bookshelf.daily-reading-game-ad bookshelf.book-activity-tag bookshelf.update-notice bookshelf.reading-time-card bookshelf.home-top-actions")
        page("navigation", "底部导航", "interface", ids = "glass-bars.page navigation.red-dot navigation.account-unread navigation.activity-tab navigation.tab-order")
        val glassRows = listOf("glass-bars.enabled") + listOf("main", "detail", "directory").flatMap { place ->
            listOf("enabled", "height", "width", "bottom-gap").map { "glass-bars.$place.$it" }
        } + listOf("blur", "refraction", "highlight", "shadow", "tilt", "animation", "base").map { "glass-bars.$it" }
        page("glass", "液态玻璃底栏", "interface", "三处独立尺寸，共用效果", glassRows.joinToString(" "))
        page("page-customization", "页面自定义", "interface", "我的页 · 精选页 · 书籍详情页，调整显示内容")
        page("mine", "我的页自定义", "page-customization", ids = "page-customization.mine page-customization.mine-auto-refresh")
        page("selection", "精选页自定义", "page-customization", ids = "page-customization.selection page-customization.selection-guess")
        page("detail-layout", "书籍详情页", "page-customization", ids = "page-customization.book_detail")
        page("shield", "书籍屏蔽", "content", ids = "book-shield.enabled book-shield.file-editor-link book-shield.stats book-shield.locations book-shield.type-contains book-shield.word-range book-shield.allow-empty book-shield.quick-add")
        page("shield-quick", "快速规则", "shield", "本次会话输入合并到 YAML；删除仅移除本次新增规则", "book-shield.authors book-shield.name-keywords book-shield.full-names book-shield.types book-shield.book-ids")
        page("shield-yaml", "规则文件", "shield", ids = "book-shield.document-editor")
        page("profile", "个人主页", "content", ids = "content-tools.profile-avatar-original content-tools.profile-user-id content-tools.profile-privacy-second-page")
        page("detail-tools", "书籍详情工具", "content", ids = "content-tools.book-intro-copy content-tools.book-cover-original content-tools.role-avatar-original content-tools.fans-rank")
        page("images", "书友圈与图片", "content", ids = "content-tools.circle-copy content-tools.upload-watermark")
        page("cards", "卡牌", "content", ids = "content-tools.card-plaza-local-unlock content-tools.card-preview-long-press-save")
        page("automation", "自动任务", "account", ids = "account.auto-sign-in account.auto-invest")
        page("lookup", "用户查找", "account", ids = "maintenance.user-lookup")
        page("read-time", "阅读时长", "account", ids = "read_time.multiplier.enabled read_time.multiplier.value read_time.local_display.real read_time.report_audit read_time.cloud_probe read_time.hint")
        page("welfare", "福利中心", "account", ids = "welfare.reward-ad welfare.combo-reward welfare.reward-popup")
        page("reports", "启动与设备上报", "privacy", ids = "startup.report startup.device-track startup.fock-report startup.download-game")
        page("dns", "DNS", "privacy", ids = "network-identity.dns-enabled network-identity.dns-stack network-identity.dns-rules")
        page("dialogs", "弹窗与引导", "privacy", ids = "dialogs.privacy-update dialogs.update-check dialogs.notification-permission dialogs.teenager-entry")
        page("resolver", "解析与运行信息", "tools", ids = "diagnostics.resolver-status")
        page("probes", "日志与探针", "tools", ids = "diagnostics.detailed-log diagnostics.export-logs diagnostics.clear-logs diagnostics.discovery diagnostics.call-probe diagnostics.device-identity diagnostics.host-collect-json book-shield.trace welfare.reward-probe")
        page("cookie", "Cookie 与指纹", "tools", ids = "diagnostics.cookie-identity")
        page("backup", "配置与规则备份", "tools", ids = "maintenance.export-config maintenance.import-config maintenance.export-book-shield-rules maintenance.import-book-shield-rules")
        page("maintenance", "数据清理", "tools", ids = "maintenance.clear-host-data")
        page("about", "关于", "home", "QDHelper · 仓库与作者", "about.repository about.author")
    }.associateBy { it.id }
    val metadata: Map<String, SettingsUiMeta> = buildMap {
        destinations.values.forEach { destination -> destination.rowIds.forEach { id ->
            check(put(id, SettingsUiMeta(destination.id, riskFor(id), dependenciesFor(id))) == null) { "Duplicate presentation row: $id" }
        } }
    }

    init {
        check(rows.size == sections.sumOf { it.rows.size }) { "Duplicate business row ID" }
        check(metadata.keys == rows.keys) { "Presentation drift: missing=${rows.keys - metadata.keys}, unknown=${metadata.keys - rows.keys}" }
        destinations.values.forEach { destination ->
            val seen = mutableSetOf(destination.id)
            var parent = destination.parent
            while (parent != null) {
                check(seen.add(parent)) { "Destination cycle: ${destination.id}" }
                parent = checkNotNull(destinations[parent]) { "Unknown parent: $parent" }.parent
            }
        }
        metadata.forEach { (id, meta) -> meta.dependencies.forEach { dependency ->
            check(rows[dependency.rowId] is SettingsRow.Toggle) { "Invalid dependency for $id: ${dependency.rowId}" }
        } }
    }

    fun children(id: String) = destinations.values.filter { it.parent == id }
    fun rowsFor(id: String) = destinations.getValue(id).rowIds.map(rows::getValue)
    fun path(id: String): String {
        val destination = destinations.getValue(id)
        return if (destination.parent == null || destination.parent == "home") destination.title
        else path(destination.parent) + " > " + destination.title
    }
    fun destinationForPage(page: SettingsPageId, focusTitle: String? = null): String {
        if (page == SettingsPageIds.HOME) return "home"
        val matching = sections.filter { it.page == page }.flatMap { it.rows }
        val row = matching.firstOrNull { it.title == focusTitle || it.searchAliases.any { alias -> alias.focusTitle == focusTitle } }
            ?: matching.firstOrNull()
        return row?.let { metadata.getValue(it.id).destination } ?: "home"
    }

    fun resetRows(destination: String): List<SettingsRow> = rowsFor(destination) + children(destination).flatMap { resetRows(it.id) }

    fun resetActionSections(destination: String): List<SettingsSection> {
        val selected = resetRows(destination).map { it.id }.toSet()
        return sections.filter { section ->
            section.rows.firstOrNull()?.id in selected ||
                section.rows.any { it.id in selected && it is SettingsRow.PageCustomization } ||
                // The YAML editor and quick-rule forms edit the same document as their overview.
                (section.id == "book-shield.rules" && destination in setOf("shield-quick", "shield-yaml"))
        }
    }

    private fun riskFor(id: String) = when {
        id == "account.auto-invest" || id.startsWith("read_time.multiplier") -> SettingRisk.HighRisk
        id == "book-shield.allow-empty" -> SettingRisk.Attention
        id.startsWith("diagnostics.") || id in setOf("book-shield.trace", "welfare.reward-probe") -> SettingRisk.Diagnostic
        else -> SettingRisk.Normal
    }

    private fun dependenciesFor(id: String): List<SettingDependency> {
        fun requires(rowId: String, title: String) = SettingDependency(rowId, "需要先开启「$title」")
        return when {
            id in setOf("diagnostics.discovery", "diagnostics.call-probe", "diagnostics.device-identity", "diagnostics.host-collect-json", "book-shield.trace", "welfare.reward-probe") -> listOf(requires("diagnostics.detailed-log", "详细日志"))
            id == "glass-bars.tilt" -> listOf(requires("glass-bars.enabled", "悬浮底栏"), requires("glass-bars.highlight", "边缘高光"))
            id.startsWith("glass-bars.") && id !in setOf("glass-bars.enabled", "glass-bars.page", "glass-bars.base") -> buildList {
                add(requires("glass-bars.enabled", "悬浮底栏"))
                val place = id.split('.').getOrNull(1)
                if (place in setOf("main", "detail", "directory") && !id.endsWith(".enabled")) add(requires("glass-bars.$place.enabled", "对应位置底栏"))
            }
            id in setOf("network-identity.dns-stack", "network-identity.dns-rules") -> listOf(requires("network-identity.dns-enabled", "自定义 DNS"))
            id == "reader.free-copy-mode" -> listOf(requires("reader.free-copy", "自由复制"))
            id == "read_time.multiplier.value" -> listOf(requires("read_time.multiplier.enabled", "阅读时长倍率"))
            else -> emptyList()
        }
    }
}
