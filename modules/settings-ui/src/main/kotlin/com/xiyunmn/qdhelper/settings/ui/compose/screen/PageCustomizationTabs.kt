package com.xiyunmn.qdhelper.settings.ui.compose.screen

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xiyunmn.qdhelper.settings.api.SettingsRow
import com.xiyunmn.qdhelper.settings.ui.compose.component.ConsoleTabs
import com.xiyunmn.qdhelper.settings.ui.compose.editor.PageCustomizationScreen
import com.xiyunmn.qdhelper.settings.ui.compose.navigation.LocalSettingsEntry
import com.xiyunmn.qdhelper.settings.ui.compose.navigation.SettingsEntry
import com.xiyunmn.qdhelper.settings.ui.compose.state.SettingsController

@Composable
internal fun PageCustomizationTabs(entry: SettingsEntry, controller: SettingsController) {
    val tabs = remember { listOf("mine" to "我的页", "selection" to "精选页", "detail-layout" to "书籍详情页") }
    val selected = entry.selectedTab ?: "mine"
    val child = remember(entry, selected) { entry.tabEntries.getOrPut(selected) { SettingsEntry(entry.id, selected) } }
    LaunchedEffect(entry.focusRow) {
        val target = entry.focusRow ?: return@LaunchedEffect
        val destination = controller.catalog.metadata[target]?.destination
        if (tabs.any { it.first == destination }) {
            entry.selectedTab = destination
            entry.tabEntries.getOrPut(destination!!) { SettingsEntry(entry.id, destination) }.focusRow = target
        }
        entry.focusRow = null
    }
    Column(Modifier.fillMaxSize()) {
        ConsoleTabs(tabs, selected, { entry.selectedTab = it },
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        Box(Modifier.weight(1f)) {
            // Disposing the old editor cancels its pending scan and restores the host page.
            // Only UI position is retained; two hidden host scans must never overlap.
            key(selected) {
                CompositionLocalProvider(LocalSettingsEntry provides child) {
                    val rows = controller.catalog.rowsFor(selected)
                    PageCustomizationScreen(rows.filterIsInstance<SettingsRow.PageCustomization>().single(), controller, rows.filter { it !is SettingsRow.PageCustomization })
                }
            }
        }
    }
}
