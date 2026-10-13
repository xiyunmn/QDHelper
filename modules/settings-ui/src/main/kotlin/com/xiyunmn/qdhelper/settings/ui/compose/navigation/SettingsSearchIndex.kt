package com.xiyunmn.qdhelper.settings.ui.compose.navigation

import com.xiyunmn.qdhelper.settings.api.SettingsRow
import com.xiyunmn.qdhelper.settings.ui.compose.presentation.SettingsPresentationCatalog

internal data class SettingsSearchResult(val title: String, val rowId: String, val destination: String, val path: String, val subTarget: String?, val text: String)
internal class SettingsSearchIndex(catalog: SettingsPresentationCatalog) {
    private val entries = catalog.rows.values.filter { it.title.isNotBlank() }.flatMap { row ->
        val destination = catalog.metadata.getValue(row.id).destination
        val path = catalog.path(destination)
        fun entry(title: String, keywords: Set<String>, target: String? = null) = SettingsSearchResult(
            title, row.id, destination, path, target,
            (listOf(title, row.summary.orEmpty(), path) + row.keywords + keywords).joinToString(" ").lowercase(),
        )
        listOf(entry(row.title, emptySet())) + row.searchAliases.map { alias ->
            if (row is SettingsRow.PageLink) {
                val target = catalog.destinationForPage(row.targetPage, alias.focusTitle)
                val targetRow = catalog.rowsFor(target).firstOrNull { it.title == alias.focusTitle } ?: catalog.rowsFor(target).firstOrNull()
                entry(alias.title, alias.keywords, alias.focusTitle).copy(destination = target, rowId = targetRow?.id ?: row.id, path = catalog.path(target))
            } else entry(alias.title, alias.keywords, alias.focusTitle)
        } +
            (if (row is SettingsRow.ParagraphOrder) row.choices.map { entry(it.title, emptySet(), it.key) } else emptyList())
    }.distinct() // Identical aliases are a single search hit; the business catalog validates IDs separately.
    fun search(query: String): List<SettingsSearchResult> {
        val terms = query.trim().lowercase().split(Regex("\\s+")).filter(String::isNotBlank)
        if (terms.isEmpty()) return emptyList()
        return entries.filter { result -> terms.all(result.text::contains) }
            .sortedByDescending { it.title.equals(query.trim(), ignoreCase = true) }
    }
}
