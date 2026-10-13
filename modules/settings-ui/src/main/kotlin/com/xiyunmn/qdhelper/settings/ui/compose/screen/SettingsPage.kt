package com.xiyunmn.qdhelper.settings.ui.compose.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.xiyunmn.qdhelper.settings.api.SettingsDocumentState
import com.xiyunmn.qdhelper.settings.api.SettingsRow
import com.xiyunmn.qdhelper.settings.ui.compose.component.*
import com.xiyunmn.qdhelper.settings.ui.compose.editor.ManagedListEditor
import com.xiyunmn.qdhelper.settings.ui.compose.editor.InlineTextSetting
import com.xiyunmn.qdhelper.settings.ui.compose.editor.StartupImageSetting
import com.xiyunmn.qdhelper.settings.ui.compose.editor.PageCustomizationScreen
import com.xiyunmn.qdhelper.settings.ui.compose.navigation.SettingsEntry
import com.xiyunmn.qdhelper.settings.ui.compose.presentation.SettingsPageBlock
import com.xiyunmn.qdhelper.settings.ui.compose.presentation.settingsPageBlocks
import com.xiyunmn.qdhelper.settings.ui.compose.presentation.startupInputGroups
import com.xiyunmn.qdhelper.settings.ui.compose.row.SettingsRowRenderer
import com.xiyunmn.qdhelper.settings.ui.compose.state.SettingsController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
internal fun SettingsPage(entry: SettingsEntry, controller: SettingsController, edit: (SettingsRow) -> Unit) {
    val catalog = controller.catalog
    if (entry.destination == "page-customization") {
        PageCustomizationTabs(entry, controller)
        return
    }
    val rows = catalog.rowsFor(entry.destination)
    if (entry.destination == "font-library") {
        ManagedListEditor(rows.filterIsInstance<SettingsRow.ManagedList>().single(), controller) { edit(catalog.rows.getValue("reader-font.manager-clear")) }
        return
    }
    if (entry.destination in setOf("mine", "selection", "detail-layout")) {
        PageCustomizationScreen(rows.filterIsInstance<SettingsRow.PageCustomization>().single(), controller, rows.filter { it !is SettingsRow.PageCustomization })
        return
    }
    val glassTabs = remember { listOf("main" to "主界面", "detail" to "详情页", "directory" to "目录页") }
    val glassPlace = entry.selectedTab ?: "main"
    LaunchedEffect(entry.focusRow) {
        if (entry.destination == "glass") {
            entry.focusRow?.split('.')?.getOrNull(1)?.takeIf { target -> glassTabs.any { it.first == target } }
                ?.let { entry.selectedTab = it }
        }
    }
    val blocks = remember(catalog, entry.destination, glassPlace) { settingsPageBlocks(catalog, entry.destination, glassPlace) }
    val target = entry.focusRow
    LaunchedEffect(target, glassPlace) {
        if (target != null) {
            val index = blocks.indexOfFirst { block ->
                block.rows.any { it.id == target } ||
                    block.special == "backup" && catalog.rowsFor("backup").any { it.id == target } ||
                    block.special == "shield-entry" && target == "book-shield.enabled" ||
                    block.links.contains("shield-yaml") && target == "book-shield.file-editor-link"
            }
            if (index >= 0) entry.scroll.animateScrollToItem(index)
        }
    }
    ScrollToTopContainer(entry.scroll, Modifier.imePadding()) {
        LazyColumn(Modifier.fillMaxSize(), state = entry.scroll,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(blocks, key = { it.id }, contentType = { if (it.special != null) it.special else if (it.rows.isNotEmpty()) "group" else "links" }) { block ->
                when {
                    block.rows.isNotEmpty() -> SettingsGroup(block, entry, controller, edit)
                    block.links.isNotEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        block.links.forEach { id -> key(id) { DestinationCard(id, controller) } }
                    }
                    else -> when (block.special) {
                        "resolver" -> ConsoleSurface { ResolverSummary(controller) }
                        "shield-overview" -> ShieldOverview(controller)
                        "shield-entry" -> ConsoleSurface {
                            Column {
                                Row(Modifier.fillMaxWidth().clickable { controller.openDestination("shield") }.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    IconTile("shield")
                                    Column(Modifier.weight(1f)) {
                                        Text("书籍屏蔽", style = MaterialTheme.typography.titleMedium)
                                        Text("规则、范围与最近统计", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Chevron()
                                }
                                val row = catalog.rows.getValue("book-shield.enabled") as SettingsRow.Toggle
                                SettingLine("启用屏蔽", onClick = { controller.toggle(row, !controller.boolean(row.key)) }) {
                                    Switch(controller.boolean(row.key), { controller.toggle(row, it) })
                                }
                                Row(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ConsoleActionButton("快速规则", { entry.expanded["quick-rules"] = entry.expanded["quick-rules"] != true },
                                        Modifier.weight(1f), tone = SemanticTone.Info,
                                        trailingIcon = if (entry.expanded["quick-rules"] == true) "up" else "down")
                                    ConsoleActionButton("完整 YAML", { controller.openDestination("shield-yaml") }, Modifier.weight(1f), tone = SemanticTone.Accent)
                                }
                                if (entry.expanded["quick-rules"] == true) {
                                    QuickRulesHint()
                                    catalog.rowsFor("shield-quick").forEach { quickRow -> key(quickRow.id) {
                                        Column { InlineTextSetting(quickRow, controller, true, null, null, null, alwaysExpanded = true, compact = true) }
                                    }
                                    }
                                }
                            }
                        }
                        "backup" -> ConsoleSurface {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                GroupHeading("配置与规则", "backup")
                                catalog.rowsFor("backup").chunked(2).forEach { pair ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        pair.forEach { row ->
                                            val label = when (row.id) {
                                                "maintenance.export-book-shield-rules" -> "导出\n内容屏蔽规则"
                                                "maintenance.import-book-shield-rules" -> "导入\n内容屏蔽规则"
                                                else -> row.title
                                            }
                                            ConsoleActionButton(label, { edit(row) }, Modifier.weight(1f),
                                                tone = if (row.id.contains("import")) SemanticTone.Accent else SemanticTone.Info)
                                        }
                                    }
                                }
                            }
                        }
                        "glass-tabs" -> ConsoleTabs(glassTabs, glassPlace, { entry.selectedTab = it })
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupHeading(title: String, category: String, expanded: Boolean? = null, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ConsoleIcon(category, Modifier.size(20.dp), categoryAccent(category))
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        if (expanded != null) ConsoleIcon(if (expanded) "up" else "down", Modifier.size(18.dp))
    }
}

@Composable
private fun QuickRulesHint() {
    Text("每行一条，自动合并到 YAML；删除只撤销本次新增规则。",
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SettingsGroup(block: SettingsPageBlock, entry: SettingsEntry, controller: SettingsController, edit: (SettingsRow) -> Unit) {
    val focus = entry.focusRow
    val quickRules = block.special == "quick-rules"
    LaunchedEffect(focus) { if (block.rows.any { it.id == focus }) entry.expanded[block.id] = true }
    val expanded = entry.expanded[block.id] == true || block.rows.any { it.id == focus }
    val visible = if (expanded) block.rows else block.rows.take(block.previewRows)
    val albumActions = visible.filter { it.id in setOf("startup.local-pick", "startup.local-clear") }
    val logActions = visible.filter { it.id in setOf("diagnostics.export-logs", "diagnostics.clear-logs") }
    val groupedInputs = startupInputGroups.filterKeys { parent -> visible.any { it.id == parent } }
    val inputIds = groupedInputs.values.flatten().toSet()
    val rendered = visible.filterNot { it.id in inputIds || albumActions.size == 2 && it.id == "startup.local-clear" ||
        logActions.size == 2 && it.id == "diagnostics.clear-logs" }
    ConsoleSurface {
        Column {
            if (quickRules) SettingLine(block.title, if (expanded) null else "作者、书名、分类与书号",
                onClick = { entry.expanded[block.id] = !expanded }) {
                ConsoleIcon(if (expanded) "up" else "down", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else if (block.title.isNotBlank()) {
                if (block.rows.size > block.previewRows) GroupHeading(block.title, entry.destination, expanded) { entry.expanded[block.id] = !expanded }
                else GroupHeading(block.title, entry.destination)
            }
            if (quickRules && expanded) QuickRulesHint()
            block.links.forEach { id ->
                val destination = controller.catalog.destinations.getValue(id)
                SettingLine(destination.title, destination.summary, onClick = { controller.openDestination(id) }) { Chevron() }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
            }
            rendered.forEachIndexed { index, row ->
                key(row.id) {
                    val requester = remember { BringIntoViewRequester() }
                    val actions = when (row.id) {
                        "startup.sync" -> listOf(row)
                        "startup.local-pick" -> albumActions
                        "diagnostics.export-logs" -> logActions
                        else -> emptyList()
                    }.filterIsInstance<SettingsRow.Action>()
                    val inputs = groupedInputs[row.id].orEmpty().map { controller.catalog.rows.getValue(it) as SettingsRow.TextInput }
                    val focused = focus == row.id || actions.any { it.id == focus } || inputs.any { it.id == focus }
                    LaunchedEffect(focused) {
                        if (focused) {
                            // First the lazy list mounts this group, then locate the individual row.
                            delay(280)
                            requester.bringIntoView()
                            delay(1600)
                            if (entry.focusRow == focus) entry.focusRow = null
                        }
                    }
                    Box(Modifier.fillMaxWidth().bringIntoViewRequester(requester).settingsFocusHighlight(focused)) {
                        if (inputs.isNotEmpty()) StartupImageSetting(row as SettingsRow.Toggle, inputs, controller)
                        else if (row.id == "diagnostics.export-logs") LogFileActions(actions, edit)
                        else if (actions.isNotEmpty()) StartupImageActions(actions, controller, edit)
                        else if (quickRules) Column { InlineTextSetting(row, controller, true, null, null, null, alwaysExpanded = true, compact = true) }
                        else SettingsRowRenderer(row, controller, edit)
                    }
                    if (index != rendered.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                }
            }
            if (!quickRules && !expanded && block.rows.size > block.previewRows) {
                TextButton({ entry.expanded[block.id] = true }, Modifier.fillMaxWidth()) {
                    Text("展开其余 ${block.rows.size - visible.size} 项")
                    Spacer(Modifier.width(6.dp)); ConsoleIcon("down", Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun LogFileActions(actions: List<SettingsRow.Action>, edit: (SettingsRow) -> Unit) {
    Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        ConsoleActionGrid(actions.size) { index, modifier ->
            val row = actions[index]
            ConsoleActionButton(row.title, { edit(row) }, modifier,
                tone = if (row.id == "diagnostics.clear-logs") SemanticTone.Error else SemanticTone.Info)
        }
    }
}

@Composable
private fun StartupImageActions(actions: List<SettingsRow.Action>, controller: SettingsController, edit: (SettingsRow) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ConsoleActionGrid(actions.size) { index, modifier ->
            val row = actions[index]
            ConsoleActionButton(
                when (row.id) {
                    "startup.sync" -> "同步启动图"
                    "startup.local-pick" -> "从相册选择"
                    else -> "清除相册图"
                },
                { edit(row) }, modifier, enabled = controller.dependency(row) == null,
                tone = if (row.id == "startup.local-clear") SemanticTone.Error else SemanticTone.Info,
            )
        }
        Text(if (actions.first().id == "startup.sync") "同步当前图片源，供下次冷启动使用。"
            else "选图后可旋转、裁剪；清除会关闭相册启动图。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ShieldOverview(controller: SettingsController) {
    val document = controller.catalog.rows.getValue("book-shield.document-editor") as SettingsRow.DocumentEditor
    val context = controller.activity.applicationContext
    val state by produceState<SettingsDocumentState?>(null, controller.revision.intValue) {
        value = withContext(Dispatchers.IO) { runCatching { document.controller.state(context) }.getOrNull() }
    }
    val data = state
    ConsoleSurface {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("规则概览", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Text(data?.let { "${it.ruleCount} 条规则" } ?: "读取中", style = MaterialTheme.typography.labelLarge, color = semanticColor(SemanticTone.Info))
            }
            if (data != null) {
                val entries = data.ruleBreakdown.entries.toList()
                val accents = listOf(categoryAccent("shield"), categoryAccent("reading"), categoryAccent("interface"), categoryAccent("account"))
                if (data.ruleCount > 0) Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))) {
                    entries.forEachIndexed { index, value -> if (value.value > 0) Box(Modifier.weight(value.value.toFloat()).fillMaxHeight().background(accents[index % accents.size])) }
                } else Text("尚未添加规则", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                entries.chunked(3).forEach { chunk ->
                    Row(Modifier.fillMaxWidth()) {
                        chunk.forEach { value ->
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(value.value.toString(), style = MaterialTheme.typography.titleLarge, color = semanticColor(SemanticTone.Info))
                                Text(value.key, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (data.dirty) Text("有未保存的 YAML 草稿", color = semanticColor(SemanticTone.Warning), style = MaterialTheme.typography.bodySmall)
                data.errorMessage?.let { Text(it, color = semanticColor(SemanticTone.Error), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
