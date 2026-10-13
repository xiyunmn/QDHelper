package com.xiyunmn.qdhelper.settings.ui.compose.editor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.xiyunmn.qdhelper.settings.ui.compose.navigation.LocalSettingsEntry
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.settings.api.SettingsRow
import com.xiyunmn.qdhelper.settings.ui.compose.component.*
import com.xiyunmn.qdhelper.settings.ui.compose.state.SettingsController
import com.xiyunmn.qdhelper.settings.ui.readSettingsCsvSelection
import com.xiyunmn.qdhelper.settings.ui.updateSettingsCsvSelection
import kotlinx.coroutines.*

@Composable
internal fun PageCustomizationScreen(row: SettingsRow.PageCustomization, settings: SettingsController, extraRows: List<SettingsRow> = emptyList()) {
    val controller = row.controller
    val entry = LocalSettingsEntry.current
    val activity = settings.activity
    val app = activity.applicationContext
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var scanning by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var restore by remember { mutableStateOf<AutoCloseable?>(null) }
    var clear by remember { mutableStateOf(false) }
    fun finish() {
        job?.cancel(); job = null
        controller.cancelPendingScan(app)
        restore?.close(); restore = null
        scanning = false
    }
    DisposableEffect(controller) {
        val listener = controller.addScanResultListener { result ->
            scope.launch(Dispatchers.Main.immediate) {
                revision++
                if (scanning && result.matchedPage) {
                    settings.notify("已扫描到 ${result.count} 项")
                    if (!controller.hasPendingScan(app)) finish()
                }
            }
        }
        onDispose { listener.close(); finish() }
    }
    fun start() {
        if (controller.fixedItems) {
            val result = controller.refreshKnownItems(app); revision++; settings.notify("已刷新 ${result.count} 项"); return
        }
        if (!controller.requestPendingScan(app)) { settings.notify("扫描正在进行中"); return }
        restore = controller.openHostPageForPendingScan(activity)
        if (restore == null) { controller.cancelPendingScan(app); settings.notify("无法后台打开页面，请在起点主界面重试"); return }
        scanning = true
        job = scope.launch {
            try {
                var elapsed = 0L
                for (time in listOf(300L, 900L, 1800L, 3200L, 5200L)) {
                    delay(time - elapsed); elapsed = time
                    if (!controller.hasPendingScan(app)) break
                    // Always scan the host Activity tree, never this dialog's Compose tree.
                    val root = activity.window.decorView
                    if (controller.id == "selection") controller.consumePendingScanIfMatchesAsync(root) { result ->
                        scope.launch(Dispatchers.Main.immediate) {
                            if (scanning && result != null) { revision++; if (!controller.hasPendingScan(app)) finish() }
                        }
                    } else controller.consumePendingScanIfMatches(root)?.let { revision++; settings.notify("已扫描到 ${it.count} 项") }
                }
                // An asynchronous host result may arrive after the last scheduled attempt.
                if (controller.hasPendingScan(app)) delay(10_000)
                if (controller.hasPendingScan(app)) settings.notify("扫描超时，可稍后重试")
            } finally { controller.cancelPendingScan(app); restore?.close(); restore = null; scanning = false; revision++ }
        }
    }
    val items = remember(revision, settings.revision.intValue) { controller.storedItems(app) }
    val valid = items.map { it.key }
    val hidden = readSettingsCsvSelection(settings.string(controller.hiddenKey), valid)
    val focused = entry.focusRow
    LaunchedEffect(focused) {
        if (focused != null) {
            entry.scroll.animateScrollToItem(0)
            delay(1600)
            if (entry.focusRow == focused) entry.focusRow = null
        }
    }
    val enabled = settings.boolean(controller.enabledKey)
    ScrollToTopContainer(entry.scroll) {
        LazyColumn(
            Modifier.fillMaxSize(), state = entry.scroll,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item("configuration") {
                ConsoleSurface {
                    Column {
                        Box(Modifier.settingsFocusHighlight(focused == row.id)) {
                            SettingLine("启用自定义", if (controller.fixedItems) "调整书籍详情页的模块显示。" else row.summary) {
                                Switch(enabled, { settings.putBoolean(controller.enabledKey, it) })
                            }
                        }
                        extraRows.forEach { extra ->
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f))
                            Box(Modifier.settingsFocusHighlight(focused == extra.id)) {
                                com.xiyunmn.qdhelper.settings.ui.compose.row.SettingsRowRenderer(extra, settings)
                            }
                        }
                    }
                }
            }
            item("scan") {
                ConsoleSurface {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("页面项目", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            Text("${items.size} 项 · 隐藏 ${hidden.size} 项", style = MaterialTheme.typography.labelMedium, color = semanticColor(SemanticTone.Info))
                        }
                        Text(
                            if (scanning) "正在后台读取页面，完成后会自动更新列表。"
                            else if (controller.fixedItems) "使用固定候选项目，可随时刷新列表。"
                            else "扫描当前页面，获取可隐藏的卡片和入口。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ConsoleActionButton(if (scanning) "取消扫描" else if (controller.fixedItems) "刷新项目" else "扫描页面",
                                { if (scanning) finish() else start() }, Modifier.weight(1f).heightIn(min = 46.dp),
                                icon = "resolver", busy = scanning)
                            ConsoleActionButton("清空配置", { clear = true }, Modifier.weight(1f).heightIn(min = 46.dp),
                                tone = SemanticTone.Error, icon = "close")
                        }
                    }
                }
            }
            item("help") {
                Text(
                    if (enabled) "开关控制项目是否显示，修改后自动保存。"
                    else "自定义已暂停；修改下方项目会自动启用。",
                    Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (items.isEmpty()) item("empty") {
                ConsoleSurface {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ConsoleIcon("interface", Modifier.size(28.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (scanning) "等待扫描结果" else "暂无页面项目", style = MaterialTheme.typography.titleSmall)
                        Text(if (controller.fixedItems) "点击上方「刷新项目」加载候选项。" else "点击上方「扫描页面」获取候选项。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(items, key = { "candidate:${it.key}" }) { item ->
                val visible = item.key !in hidden
                Surface(
                    Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                    color = if (visible) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Row(
                        Modifier.toggleable(value = visible, role = Role.Switch) { show ->
                            val selection = updateSettingsCsvSelection(settings.string(controller.hiddenKey), valid, key = item.key, selected = !show)
                            val success = SettingsRuntime.prefs(app).edit().putString(controller.hiddenKey, selection.serialized).putBoolean(controller.enabledKey, true).commit()
                            if (!success) settings.notify("保存失败")
                            settings.refreshValues()
                        }.heightIn(min = 64.dp).padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ConsoleIcon(if (visible) "reveal" else "mask", Modifier.size(20.dp),
                            if (visible) semanticColor(SemanticTone.Info) else MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(item.label, fontSize = 15.sp, color = if (visible) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(if (visible) "显示" else "隐藏", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(visible, onCheckedChange = null)
                    }
                }
            }
        }
    }
    if (clear) ConsoleConfirmationDialog("清空${controller.label}配置？", "将删除候选项记录和隐藏选择。", "清空",
        onConfirm = { finish(); settings.notify(if (controller.clear(app)) "已清空" else "清空失败"); revision++; settings.refreshValues(); clear = false },
        onDismiss = { clear = false }, tone = SemanticTone.Error)
}
