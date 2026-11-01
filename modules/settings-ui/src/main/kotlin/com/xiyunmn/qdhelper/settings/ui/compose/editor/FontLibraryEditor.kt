package com.xiyunmn.qdhelper.settings.ui.compose.editor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiyunmn.qdhelper.settings.api.*
import com.xiyunmn.qdhelper.settings.ui.compose.component.*
import com.xiyunmn.qdhelper.settings.ui.compose.navigation.LocalSettingsEntry
import com.xiyunmn.qdhelper.settings.ui.compose.state.SettingsController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ManagedListEditor(row: SettingsRow.ManagedList, controller: SettingsController, clear: (() -> Unit)? = null) {
    val entry = LocalSettingsEntry.current
    val scope = rememberCoroutineScope()
    val context = controller.activity.applicationContext
    val fonts = row.controller as? SettingsFontLibraryController
    val dragHost = LocalSettingsDrag.current
    var revision by remember(row.id) { mutableIntStateOf(0) }
    var items by remember(row.id) { mutableStateOf<List<SettingsManagedListItem>>(emptyList()) }
    var loading by remember(row.id) { mutableStateOf(true) }
    var loadFailed by remember(row.id) { mutableStateOf(false) }
    var managing by remember(row.id) { mutableStateOf(false) }
    var selected by remember(row.id) { mutableStateOf<Set<String>>(emptySet()) }
    var deleting by remember(row.id) { mutableStateOf(false) }
    var delete by remember(row.id) { mutableStateOf<SettingsManagedListItem?>(null) }
    var batch by remember(row.id) { mutableStateOf<List<SettingsManagedListItem>?>(null) }
    DisposableEffect(row.controller) {
        val listener = row.controller.addChangedListener {
            scope.launch(Dispatchers.Main.immediate) {
                // A batch refreshes once on completion, not once per removed file.
                if (!deleting) { revision++; controller.refreshValues() }
            }
        }
        onDispose { listener.close() }
    }
    LaunchedEffect(row.controller, revision, controller.revision.intValue, deleting) {
        if (deleting) return@LaunchedEffect
        if (items.isEmpty()) loading = true
        try {
            items = withContext(Dispatchers.IO) { row.controller.items(context) }
            loadFailed = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            loadFailed = true
        } finally { if (currentCoroutineContext().isActive) loading = false }
    }
    LaunchedEffect(items) {
        selected = selected.intersect(items.mapTo(hashSetOf()) { it.key })
        if (items.isEmpty() && !loading && !deleting) managing = false
    }
    LaunchedEffect(entry.focusRow, items.size) {
        val target = entry.focusRow ?: return@LaunchedEffect
        val index = when (target) { "reader-font.manager-import", "reader-font.manager-clear" -> 1; else -> 2 }
        entry.scroll.animateScrollToItem(index.coerceAtMost(items.size + 2))
        entry.focusRow = null
    }
    val back = rememberUpdatedState {
        when {
            deleting -> true
            managing -> { managing = false; selected = emptySet(); true }
            else -> false
        }
    }
    DisposableEffect(entry) {
        val handler = { back.value() }
        entry.consumeBack = handler
        onDispose { if (entry.consumeBack === handler) entry.consumeBack = null }
    }
    val dragGroup = rememberDragGroup(row.id, items.map { DragItem(it.key, it.title) }) { keys ->
        val result = row.controller.reorder(controller.activity, keys)
        if (!result.success) controller.notify(result.message)
        revision++
        result.success
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            ScrollToTopContainer(entry.scroll) {
                LazyColumn(Modifier.fillMaxSize(), state = entry.scroll,
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = if (managing) 12.dp else 80.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item("description") { ConsoleSurface {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("共享字体库作用域", color = semanticColor(SemanticTone.Info), style = MaterialTheme.typography.titleMedium)
                            Text("长按排序，各作用域独立选择。", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SemanticBadge("阅读正文", SemanticTone.Info)
                                SemanticBadge("全局常规", SemanticTone.Success)
                                SemanticBadge("全局强调", SemanticTone.Warning)
                            }
                        }
                    } }
                    item("actions") {
                        FontLibraryActions(managing, deleting, items.isNotEmpty() && !loading && !loadFailed,
                            onImport = { controller.openRow(controller.catalog.rows.getValue("reader-font.manager-import")) },
                            onClear = clear,
                            onManage = if (fonts == null) null else ({
                                dragHost.cancel()
                                managing = !managing
                                selected = emptySet()
                            }))
                    }
                    if (loadFailed) item("error") {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("字体库读取失败", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton({ revision++ }) { Text("重试") }
                        }
                    }
                    if (items.isEmpty() && !loadFailed) item("empty") {
                        Text(if (loading) "正在读取字体…" else row.emptyText, Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(items, key = { it.key }) { item ->
                        val preview by rememberFontPreview(fonts, item.key, revision)
                        if (managing) FontLibraryItem(item, preview, managing = true, selected = item.key in selected,
                            enabled = !deleting, onSelected = { checked ->
                                selected = if (checked) selected + item.key else selected - item.key
                            }, onDelete = {})
                        else DragRow(dragGroup, item.key, trailingExclusion = 54.dp) {
                            FontLibraryItem(item, preview, onDelete = { delete = item })
                        }
                    }
                }
            }
        }
        if (managing) FontBatchBar(items.size, selected.size, deleting,
            onSelectAll = {
                selected = if (selected.size == items.size) emptySet() else items.mapTo(linkedSetOf()) { it.key }
            },
            onDelete = { batch = items.filter { it.key in selected } })
    }
    delete?.let { target -> ConsoleConfirmationDialog("删除字体？", "删除「${target.title}」并清除使用该字体的角色选择。", "删除",
        onConfirm = {
            val result = row.controller.delete(controller.activity, target.key)
            controller.notify(result.message.ifBlank { if (result.success) "已删除" else "删除失败" })
            revision++; delete = null
        }, onDismiss = { delete = null }, tone = SemanticTone.Error) }
    batch?.takeIf { it.isNotEmpty() }?.let { targets ->
        ConsoleConfirmationDialog("删除所选字体？",
            "将删除以下 ${targets.size} 款字体，并清除它们的使用设置：\n\n" + targets.joinToString("\n") { it.title }, "删除",
            onConfirm = {
                batch = null
                val source = fonts
                if (source != null && !deleting) {
                    deleting = true
                    val keys = targets.map { it.key }
                    scope.launch {
                        try {
                            val result = withContext(Dispatchers.IO) {
                                val job = currentCoroutineContext()
                                source.deleteSelected(context, keys) { job.isActive }
                            }
                            selected -= result.removedKeys
                            controller.notify(result.message)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            controller.notify("部分字体未删除，请刷新后重试")
                        } finally {
                            if (currentCoroutineContext().isActive && !controller.closed) {
                                deleting = false
                                revision++
                                controller.refreshValues()
                            }
                        }
                    }
                }
            }, onDismiss = { batch = null }, tone = SemanticTone.Error)
    }
}

@Composable
private fun FontLibraryActions(managing: Boolean, deleting: Boolean, canManage: Boolean,
    onImport: () -> Unit, onClear: (() -> Unit)?, onManage: (() -> Unit)?) {
    val scale = LocalDensity.current.fontScale
    val labels = buildList {
        add("导入字体")
        if (onClear != null) add("清除全部字体")
        if (onManage != null) add(if (managing) "完成" else "批量管理")
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = when {
            maxWidth >= 328.dp * scale -> 3
            maxWidth >= 224.dp * scale -> 2
            else -> 1
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            labels.chunked(columns).forEach { line ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    line.forEach { label ->
                        val isImport = label == "导入字体"
                        val clear = label == "清除全部字体"
                        val accent = semanticColor(if (clear) SemanticTone.Error else SemanticTone.Info)
                        FilledTonalButton(
                            onClick = if (isImport) onImport else if (clear) ({ onClear?.invoke() }) else ({ onManage?.invoke() }),
                            modifier = Modifier.weight(1f).heightIn(min = 44.dp), shape = RoundedCornerShape(12.dp),
                            enabled = !deleting && (if (isImport || clear) !managing else managing || canManage),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(containerColor = accent.copy(alpha = .12f), contentColor = accent),
                        ) { Text(label, fontSize = 13.sp, maxLines = 1) }
                    }
                }
            }
        }
    }
}

@Composable
private fun FontBatchBar(total: Int, selected: Int, deleting: Boolean, onSelectAll: () -> Unit, onDelete: () -> Unit) {
    val state = when { selected == 0 -> ToggleableState.Off; selected == total -> ToggleableState.On; else -> ToggleableState.Indeterminate }
    val scale = LocalDensity.current.fontScale
    val selectAll: @Composable (Modifier) -> Unit = { modifier ->
        Row(modifier.heightIn(min = 48.dp)
            .triStateToggleable(state, enabled = !deleting && total > 0, role = Role.Checkbox, onClick = onSelectAll),
            verticalAlignment = Alignment.CenterVertically) {
            TriStateCheckbox(state, onClick = null, enabled = !deleting && total > 0, modifier = Modifier.padding(end = 8.dp))
            Text("全选", style = MaterialTheme.typography.bodyMedium)
        }
    }
    val remove: @Composable (Modifier) -> Unit = { modifier ->
        ConsoleActionButton(if (deleting) "正在删除…" else "删除所选（$selected）", onDelete, modifier,
            enabled = !deleting && selected > 0, tone = SemanticTone.Error, busy = deleting)
    }
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (maxWidth < 300.dp * scale) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                selectAll(Modifier.fillMaxWidth())
                remove(Modifier.fillMaxWidth())
            } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                selectAll(Modifier.weight(1f))
                remove(Modifier)
            }
        }
    }
}
