package com.xiyunmn.qdhelper.settings.ui.compose.screen

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.xiyunmn.qdhelper.settings.api.SettingsRow
import com.xiyunmn.qdhelper.settings.ui.compose.component.*
import com.xiyunmn.qdhelper.settings.ui.compose.editor.*
import com.xiyunmn.qdhelper.settings.ui.compose.navigation.LocalSettingsEntry
import com.xiyunmn.qdhelper.settings.ui.compose.navigation.SettingsSearchResult
import com.xiyunmn.qdhelper.settings.ui.compose.presentation.SettingsApplyPolicy
import com.xiyunmn.qdhelper.settings.ui.compose.state.SettingsController

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun SettingsApp(controller: SettingsController, installBack: ((() -> Unit) -> Unit)) {
    val navigator = controller.navigator
    val entry = navigator.current
    var menu by remember { mutableStateOf(false) }
    var reset by remember { mutableStateOf<Boolean?>(null) }
    var choiceDialog by remember { mutableStateOf<SettingsRow?>(null) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var actionConfirmation by remember { mutableStateOf<SettingsRow.Action?>(null) }
    val searchFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val selections = remember { SettingsSelections() }
    val keyboardVisible = WindowInsets.isImeVisible
    var keyboardWasVisible by remember { mutableStateOf(false) }
    LaunchedEffect(keyboardVisible) {
        if (keyboardWasVisible && !keyboardVisible) focusManager.clearFocus(force = true)
        keyboardWasVisible = keyboardVisible
    }
    val dashboardScroll = if (query.isBlank()) entry.scroll else entry.searchScroll
    val searchCollapsed by remember(entry, dashboardScroll) {
        derivedStateOf { dashboardScroll.firstVisibleItemIndex > 0 }
    }
    val resetDestination = if (entry.destination == "page-customization") entry.selectedTab ?: "mine" else entry.destination
    fun cancelSearch() { searching = false; query = ""; focusManager.clearFocus() }
    LaunchedEffect(searching) { if (searching) searchFocus.requestFocus() }
    SideEffect { installBack {
        when {
            choiceDialog != null -> choiceDialog = null
            menu -> menu = false
            reset != null -> reset = null
            selections.dismiss() -> focusManager.clearFocus()
            searching -> cancelSearch()
            query.isNotEmpty() -> cancelSearch()
            !navigator.back() -> controller.closePanel()
        }
    } }
    fun edit(row: SettingsRow) {
        focusManager.clearFocus()
        if (row is SettingsRow.Action && row.id in setOf("reader-font.manager-clear", "startup.local-clear")) actionConfirmation = row
        else if (row is SettingsRow.SingleChoice || row is SettingsRow.MultiChoice || row is SettingsRow.DynamicChoice) choiceDialog = row
        else controller.openRow(row)
    }
    val pending = SettingsApplyPolicy.pending(controller.catalog, controller::value)
    CompositionLocalProvider(LocalSettingsSelections provides selections) {
    Scaffold(
        modifier = Modifier.dismissSelectionOnTap(selections),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(entry.editor?.let { controller.catalog.rows.getValue(it).title } ?: controller.catalog.destinations.getValue(entry.destination).title, maxLines = 1) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    actions = {
                        AnimatedVisibility(!searching && (entry.destination != "home" || searchCollapsed), enter = fadeIn(tween(120)), exit = fadeOut(tween(120))) {
                            ConsoleIconButton("search", "搜索设置") { searching = true }
                        }
                        ConsoleIconButton("restart", "重启起点") { controller.restart() }
                        Box {
                            ConsoleIconButton("more", "更多操作") { menu = true }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem({ Text(if (entry.destination == "page-customization") "重置当前标签页" else "重置当前页面") }, { menu = false; reset = false })
                                DropdownMenuItem({ Text("恢复模块默认设置") }, { menu = false; reset = true })
                                DropdownMenuItem({ Text("重启起点") }, { menu = false; controller.restart() })
                            }
                        }
                    },
                )
                // The actual input stays mounted while dashboard results replace the body.
                if (searching) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        ConsoleTextField(
                            query, { query = it; if (it.isNotEmpty()) searching = true },
                            Modifier.weight(1f).focusRequester(searchFocus), singleLine = true,
                            placeholder = { Text("搜索设置、功能或关键词", style = MaterialTheme.typography.bodyMedium) },
                            leadingIcon = { ConsoleIcon("search", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                            trailingIcon = { if (query.isNotEmpty()) ConsoleIconButton("close", "清空搜索") { query = "" } },
                        )
                        if (searching) TextButton(::cancelSearch) { Text("取消") }
                    }
                }
            }
        },
        bottomBar = {
            if (pending.isNotEmpty()) Surface(color = MaterialTheme.colorScheme.primaryContainer) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${pending.size} 项修改需重启起点才能完全生效", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton({ controller.restart() }) { Text("立即重启") }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            if (searching) SearchScreen(controller, query) { result ->
                cancelSearch()
                val row = controller.catalog.rows.getValue(result.rowId)
                if (result.subTarget != null && row is SettingsRow.ParagraphOrder) controller.openRow(row, result.subTarget)
                else controller.openDestination(result.destination, row.id)
            } else AnimatedContent(entry, modifier = Modifier.fillMaxSize(), transitionSpec = {
                val direction = if (navigator.forward) 1 else -1
                ((slideInHorizontally(tween(180)) { it / 10 * direction } + fadeIn(tween(150))) togetherWith
                    (slideOutHorizontally(tween(180)) { -it / 10 * direction } + fadeOut(tween(120)))).using(null)
            }, label = "settings-page", contentKey = { it.id }) { current ->
                // Outgoing content retains its own entry and full viewport throughout the transition.
                CompositionLocalProvider(LocalSettingsEntry provides current) {
                    SettingsDragHost(current.scroll) {
                        if (current.editor != null) SettingsEditor(controller.catalog.rows.getValue(current.editor), controller)
                        else if (current.destination == "home") Dashboard(current, controller, query, { query = it }) { result ->
                            cancelSearch()
                            val row = controller.catalog.rows.getValue(result.rowId)
                            if (result.subTarget != null && row is SettingsRow.ParagraphOrder) controller.openRow(row, result.subTarget)
                            else controller.openDestination(result.destination, row.id)
                        }
                        else SettingsPage(current, controller, ::edit)
                    }
                }
            }
        }
    }
    }
    choiceDialog?.let { row -> SettingsChoiceDialog(row, controller, onDismiss = { choiceDialog = null }) }
    reset?.let { all ->
        ConsoleConfirmationDialog(
            title = if (all) "恢复模块默认设置？" else "重置当前页面？",
            text = if (all) "模块开关、用户设置和已登记的规则重置动作将恢复默认值。此操作不可撤销。" else "重置「${controller.catalog.destinations.getValue(resetDestination).title}」拥有的设置。此操作不可撤销。",
            confirmLabel = "重置", tone = SemanticTone.Error,
            onConfirm = { controller.reset(resetDestination, all); reset = null; if (entry.editor != null) navigator.back() },
            onDismiss = { reset = null })
    }
    actionConfirmation?.let { row ->
        ConsoleConfirmationDialog(row.title + "？", row.summary, row.actionText,
            onConfirm = { actionConfirmation = null; controller.openRow(row) },
            onDismiss = { actionConfirmation = null }, tone = SemanticTone.Error)
    }
}

@Composable
private fun SearchScreen(controller: SettingsController, query: String, select: (SettingsSearchResult) -> Unit) {
    val results = remember(query) { controller.search.search(query) }
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item("count") { Text(if (query.isBlank()) "输入标题、说明或关键词" else "找到 ${results.size} 项", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(results, key = { "${it.rowId}:${it.title}:${it.subTarget}" }) { result ->
            ConsoleSurface { SettingLine(result.title, result.path, onClick = { select(result) }) { Chevron() } }
        }
    }
}
