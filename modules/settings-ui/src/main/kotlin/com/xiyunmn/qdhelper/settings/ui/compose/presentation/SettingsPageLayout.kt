package com.xiyunmn.qdhelper.settings.ui.compose.presentation

import com.xiyunmn.qdhelper.settings.api.SettingsRow

/** A block has its own stable identity, independent of a business section reused elsewhere. */
internal data class SettingsPageBlock(
    val id: String,
    val title: String = "",
    val rows: List<SettingsRow> = emptyList(),
    val links: List<String> = emptyList(),
    val previewRows: Int = Int.MAX_VALUE,
    val special: String? = null,
)

internal fun settingsPageBlocks(catalog: SettingsPresentationCatalog, destination: String, glassPlace: String = "main"): List<SettingsPageBlock> = buildList {
    fun group(id: String, preview: Int = Int.MAX_VALUE) {
        val page = catalog.destinations.getValue(id)
        add(SettingsPageBlock("group:$id", page.title, catalog.rowsFor(id), previewRows = preview))
    }
    fun links(id: String, vararg targets: String) { add(SettingsPageBlock("links:$id", links = targets.toList())) }
    fun special(id: String) { add(SettingsPageBlock("special:$id", special = id)) }
    when (destination) {
        "reading" -> {
            group("reader-clean"); group("copy"); group("background")
            add(SettingsPageBlock("group:fonts", "字体",
                catalog.rowsFor("fonts").filterNot { it.id in sharedFontLibraryLinks }, links = listOf("font-library")))
        }
        "interface" -> {
            links("layout", "page-customization")
            group("splash", 3); group("bookshelf", 3); group("navigation")
        }
        "content" -> {
            special("shield-entry")
            group("detail-tools"); group("profile"); group("images"); group("cards")
        }
        "account" -> { group("automation"); group("read-time"); group("welfare"); group("lookup") }
        "privacy" -> { group("reports"); group("dns"); group("identity"); group("dialogs") }
        "tools" -> {
            special("resolver")
            links("cookie", "cookie")
            special("backup")
            group("probes", 3)
            add(SettingsPageBlock("maintenance:clear", rows = catalog.rowsFor("maintenance")))
        }
        "shield" -> {
            add(SettingsPageBlock("shield:enabled", rows = listOf(catalog.rows.getValue("book-shield.enabled"))))
            links("shield-editors", "shield-yaml")
            add(SettingsPageBlock("shield:stats", rows = listOf(catalog.rows.getValue("book-shield.stats"))))
            special("shield-overview")
            add(SettingsPageBlock("shield:locations", "生效位置", listOf(catalog.rows.getValue("book-shield.locations"))))
            add(SettingsPageBlock("group:shield-quick", "快速规则", catalog.rowsFor("shield-quick"), previewRows = 0, special = "quick-rules"))
            add(SettingsPageBlock("shield:matching", "匹配行为", listOf("book-shield.type-contains", "book-shield.word-range", "book-shield.allow-empty", "book-shield.quick-add").map(catalog.rows::getValue)))
        }
        "glass" -> {
            add(SettingsPageBlock("glass:enabled", rows = listOf(catalog.rows.getValue("glass-bars.enabled"))))
            special("glass-tabs")
            val rows = catalog.rowsFor("glass").filter { row ->
                row.id != "glass-bars.enabled" && (row.id.split('.').getOrNull(1) !in setOf("main", "detail", "directory") || row.id.startsWith("glass-bars.$glassPlace."))
            }
            rows.groupBy { catalog.sourceSection.getValue(it.id).id }.forEach { (section, owned) ->
                add(SettingsPageBlock("glass:$section", catalog.sourceSection.getValue(owned.first().id).title, owned))
            }
        }
        else -> {
            catalog.children(destination).takeIf { it.isNotEmpty() }?.let { children -> links("children:$destination", *children.map { it.id }.toTypedArray()) }
            catalog.rowsFor(destination).filterNot { destination == "fonts" && it.id in sharedFontLibraryLinks }
                .groupBy { catalog.sourceSection.getValue(it.id).id }.forEach { (section, rows) ->
                add(SettingsPageBlock("group:$destination:$section", catalog.sourceSection.getValue(rows.first().id).title, rows))
            }
        }
    }
}.also { blocks ->
    check(blocks.map { it.id }.toSet().size == blocks.size) { "Duplicate settings layout block: $destination" }
    blocks.forEach { block ->
        check(block.rows.map { it.id }.toSet().size == block.rows.size) { "Duplicate settings row in ${block.id}" }
        block.links.forEach { check(it in catalog.destinations) { "Unknown linked destination: $it" } }
    }
}

internal val inlineDestinations = mapOf(
    "about" to "home",
    "shield-quick" to "shield",
    "reader-clean" to "reading", "copy" to "reading", "background" to "reading", "fonts" to "reading",
    "splash" to "interface", "bookshelf" to "interface", "navigation" to "interface",
    "profile" to "content", "detail-tools" to "content", "images" to "content", "cards" to "content",
    "automation" to "account", "read-time" to "account", "welfare" to "account", "lookup" to "account",
    "reports" to "privacy", "dns" to "privacy", "identity" to "privacy", "dialogs" to "privacy",
    "probes" to "tools", "backup" to "tools", "maintenance" to "tools",
)

internal val sharedFontLibraryLinks = setOf("reader-font.manager-link", "reader-font.global-manager-link")

/** UI grouping only; each child retains its business ID, key, callbacks and search target. */
internal val startupInputGroups = mapOf(
    "startup.custom-enabled" to listOf("startup.custom-url"),
    "startup.official-enabled" to listOf("startup.official-url", "startup.official-paper"),
)
