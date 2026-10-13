package com.xiyunmn.qdhelper.settings.ui.compose.screen

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiyunmn.qdhelper.host.api.gateway.ResolverStatusSnapshot
import com.xiyunmn.qdhelper.settings.api.SettingsRow
import com.xiyunmn.qdhelper.settings.ui.SettingsUiRuntime
import com.xiyunmn.qdhelper.settings.ui.compose.component.*
import com.xiyunmn.qdhelper.settings.ui.compose.navigation.SettingsEntry
import com.xiyunmn.qdhelper.settings.ui.compose.navigation.SettingsSearchResult
import com.xiyunmn.qdhelper.settings.ui.compose.state.SettingsController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

@Composable
internal fun Dashboard(entry: SettingsEntry, controller: SettingsController, query: String, update: (String) -> Unit, select: (SettingsSearchResult) -> Unit) {
    val config = SettingsUiRuntime.requireConfiguration()
    val version = remember { runCatching { controller.activity.packageManager.getPackageInfo(controller.activity.packageName, 0).versionName }.getOrNull() ?: "未知" }
    val moduleVersion by produceState(config.moduleVersion) {
        if (value.isBlank()) {
            val manager = controller.activity.packageManager
            val apk = com.xiyunmn.qdhelper.shared.compose.ModuleComposeEnvironment.moduleApkPath
            value = withContext(Dispatchers.IO) { runCatching { apk?.let { manager.getPackageArchiveInfo(it, 0)?.versionName } }.getOrNull().orEmpty() }
        }
    }
    val module = controller.catalog.rows.getValue("core.module-enabled") as SettingsRow.Toggle
    val results = remember(query) { controller.search.search(query) }
    val about = remember(controller.catalog) { controller.catalog.rowsFor("about") }
    val focus = entry.focusRow
    LaunchedEffect(focus, query) {
        val target = when {
            focus == module.id -> "status"
            about.any { it.id == focus } -> "about"
            else -> null
        }
        if (query.isBlank() && target != null) {
            val count = snapshotFlow { entry.scroll.layoutInfo.totalItemsCount }.first { it > 1 }
            entry.scroll.animateScrollToItem(if (target == "status") 1 else count - 1)
            delay(1600)
            if (entry.focusRow == focus) entry.focusRow = null
        }
    }
    LazyColumn(Modifier.fillMaxSize().imePadding(), state = if (query.isBlank()) entry.scroll else entry.searchScroll,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp)) {
        item("search") {
        ConsoleTextField(query, update, Modifier.fillMaxWidth().padding(vertical = 8.dp),
            singleLine = true,
            leadingIcon = { ConsoleIcon("search", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            trailingIcon = { if (query.isNotEmpty()) ConsoleIconButton("close", "清空搜索") { update("") } },
            placeholder = { Text("搜索设置、功能或关键词", style = MaterialTheme.typography.bodyMedium) })
        }
        if (query.isNotBlank()) {
            item("count") { Text("找到 ${results.size} 项", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(results, key = { "${it.rowId}:${it.title}:${it.subTarget}" }) { result ->
                ConsoleSurface(Modifier.padding(top = 8.dp)) { SettingLine(result.title, result.path, onClick = { select(result) }) { Chevron() } }
            }
        } else {
            item("status") {
                ConsoleSurface {
                    Column {
                        Row(Modifier.settingsFocusHighlight(focus == module.id).padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            IconTile("power")
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("模块功能", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                                Text(if (controller.boolean(module.key)) "已启用" else "已关闭，仅保留设置入口", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(controller.boolean(module.key), { controller.toggle(module, it) })
                        }
                        Text("v$moduleVersion · 宿主 $version · Android ${Build.VERSION.RELEASE}", Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                        ResolverSummary(controller)
                    }
                }
            }
            item("features-label") { SectionLabel("功能") }
            items(listOf("reading", "interface", "content", "account", "privacy", "tools"), key = { it }) { id ->
                DestinationCard(id, controller, Modifier.padding(bottom = 10.dp))
            }
            item("about-label") { SectionLabel("关于") }
            item("about") {
                ConsoleSurface {
                    Column {
                        about.forEachIndexed { index, row ->
                            Box(Modifier.settingsFocusHighlight(focus == row.id)) {
                                SettingLine(row.title, row.summary, onClick = { controller.openRow(row) },
                                    leading = { ConsoleIcon(row.id, color = semanticColor(if (row.id == "about.repository") SemanticTone.Info else SemanticTone.Accent)) }) { Chevron() }
                            }
                            if (index < about.lastIndex) HorizontalDivider(
                                Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ResolverSummary(controller: SettingsController) {
    val gateway = SettingsUiRuntime.requireConfiguration().resolver
    val snapshot by produceState<ResolverStatusSnapshot?>(null, gateway, controller.revision.intValue) {
        value = withContext(Dispatchers.Default) { runCatching { gateway?.snapshot() }.getOrNull() }
    }
    val state = snapshot
    val summary = state?.let {
        if (it.unavailableReason != null) "暂不可用：${it.unavailableReason}"
        else if (!it.prewarmAttempted) "尚未预热"
        else "${it.success} / ${it.total} 个目标已解析"
    } ?: "读取中"
    val statusTone = when {
        state?.unavailableReason != null -> SemanticTone.Error
        state == null -> SemanticTone.Info
        !state.prewarmAttempted || state.success < state.total -> SemanticTone.Warning
        else -> SemanticTone.Success
    }
    Row(Modifier.fillMaxWidth().clickable { controller.openDestination("resolver") }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state != null && state.prewarmAttempted && state.total > 0 && state.unavailableReason == null) {
            CircularProgressIndicator(progress = { (state.success.toFloat() / state.total).coerceIn(0f, 1f) },
                modifier = Modifier.size(32.dp), color = semanticColor(statusTone),
                trackColor = categoryAccent("resolver").copy(alpha = .12f), strokeWidth = 4.dp)
        } else IconTile("resolver")
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("DexKit 解析状态", style = MaterialTheme.typography.titleMedium)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = semanticColor(statusTone))
        }
        Chevron()
    }
}

@Composable
internal fun DestinationCard(id: String, controller: SettingsController, modifier: Modifier = Modifier) {
    val destination = controller.catalog.destinations.getValue(id)
    ConsoleSurface(modifier) {
        Row(Modifier.fillMaxWidth().clickable { controller.openDestination(id) }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(id)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(destination.title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                if (destination.summary.isNotBlank()) Text(destination.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Chevron()
        }
    }
}
