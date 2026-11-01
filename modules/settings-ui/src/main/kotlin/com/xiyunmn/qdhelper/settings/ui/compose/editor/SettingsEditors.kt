package com.xiyunmn.qdhelper.settings.ui.compose.editor

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.xiyunmn.qdhelper.settings.ui.compose.navigation.LocalSettingsEntry
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.settings.api.*
import com.xiyunmn.qdhelper.settings.ui.compose.component.*
import com.xiyunmn.qdhelper.settings.ui.compose.state.SettingsController
import com.xiyunmn.qdhelper.settings.ui.compose.state.SettingsResultSink
import com.xiyunmn.qdhelper.settings.ui.readSettingsCsvSelection
import com.xiyunmn.qdhelper.settings.ui.updateSettingsCsvSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

@Composable
internal fun SettingsEditor(row: SettingsRow, controller: SettingsController) {
    when (row) {
        is SettingsRow.DocumentEditor -> DocumentEditor(row, controller)
        is SettingsRow.PageCustomization -> PageCustomizationScreen(row, controller)
        is SettingsRow.ManagedList -> ManagedListEditor(row, controller)
        is SettingsRow.OrderedChoice, is SettingsRow.ParagraphOrder -> ReorderEditor(row, controller)
        is SettingsRow.TextInput, is SettingsRow.SessionTextInput -> TextEditor(row, controller)
        is SettingsRow.DynamicChoice, is SettingsRow.SingleChoice, is SettingsRow.MultiChoice -> {
            val entry = LocalSettingsEntry.current
            ChoiceEditor(row, controller, entry.scroll)
        }
        is SettingsRow.Action -> row.tool?.let { SettingsToolScreen(it, controller) }
        else -> Unit
    }
}

@Composable
private fun TextEditor(row: SettingsRow, controller: SettingsController) {
    val external = when (row) {
        is SettingsRow.TextInput -> controller.string(row.key)
        is SettingsRow.SessionTextInput -> row.valueProvider.value(controller.activity)
        else -> return
    }
    var text by remember(row.id, controller.resetRevision.intValue, external) { mutableStateOf(external) }
    var invalid by remember(row.id, controller.resetRevision.intValue, external) { mutableStateOf(false) }
    val single = when (row) { is SettingsRow.TextInput -> row.singleLine; is SettingsRow.SessionTextInput -> row.singleLine; else -> true }
    val hint = when (row) { is SettingsRow.TextInput -> row.hint; is SettingsRow.SessionTextInput -> row.hint; else -> "" }
    Column(Modifier.fillMaxSize().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(row.summary.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ConsoleTextField(text, { value ->
            text = value
            val validator = when (row) { is SettingsRow.TextInput -> row.validator; is SettingsRow.SessionTextInput -> row.validator; else -> null }
            invalid = validator?.invoke(value) == false
            if (!invalid) when (row) {
                is SettingsRow.TextInput -> if (controller.putString(row.key, value)) row.onChanged?.run(controller.scope, value)
                is SettingsRow.SessionTextInput -> row.onChanged.run(controller.scope, value)
                else -> Unit
            }
        }, Modifier.fillMaxWidth().then(if (!single) Modifier.weight(1f) else Modifier),
            label = { Text(row.title) }, placeholder = { Text(hint) }, singleLine = single, isError = invalid, fillHeight = !single,
            supportingText = { Text(if (invalid) "格式不正确；尚未保存此输入" else "修改自动保存") })
    }
}

@Composable
internal fun ChoiceEditor(row: SettingsRow, controller: SettingsController, scroll: LazyListState = rememberLazyListState(), onNavigate: () -> Unit = {}, modifier: Modifier = Modifier) {
    val revision = controller.revision.intValue
    // Read external state in composition, not in the cached LazyColumn content builder.
    // The revision must invalidate the snapshot so an open dialog updates after a write.
    val dynamicState = remember(row, revision) {
        (row as? SettingsRow.DynamicChoice)?.let {
            it.controller.items(controller.activity) to it.controller.selectedKey(controller.activity)
        }
    }
    LazyColumn(modifier.fillMaxWidth(), state = scroll, contentPadding = PaddingValues(bottom = 12.dp)) {
        if (!row.summary.isNullOrBlank()) item("description") { Text(row.summary.orEmpty(), Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        when (row) {
            is SettingsRow.SingleChoice -> items(row.choices, key = { it.key }) { choice ->
                SettingLine(choice.title, choice.summary, onClick = { controller.putString(row.key, choice.key) }) {
                    RadioButton(controller.string(row.key) == choice.key, { controller.putString(row.key, choice.key) })
                }
            }
            is SettingsRow.MultiChoice -> items(row.choices, key = { it.key }) { choice ->
                val valid = row.choices.map { it.key }
                val selected = readSettingsCsvSelection(controller.string(row.key), valid, row.defaultSelected)
                fun change() {
                    val next = updateSettingsCsvSelection(controller.string(row.key), valid, row.defaultSelected, choice.key, choice.key !in selected)
                    controller.putString(row.key, next.serialized)
                }
                SettingLine(choice.title, choice.summary, onClick = ::change) { Checkbox(choice.key in selected, { change() }) }
            }
            is SettingsRow.DynamicChoice -> {
                val (options, selected) = checkNotNull(dynamicState)
                fun select(key: String?) {
                    val result = row.controller.select(controller.activity, key)
                    if (!result.success) controller.notify(result.message.ifBlank { "更新失败" })
                    controller.refreshValues()
                }
                item("default") { SettingLine(row.defaultChoiceTitle, onClick = { select(null) }) { RadioButton(selected == null, { select(null) }) } }
                items(options, key = { it.key }) { choice -> SettingLine(choice.title, choice.summary, onClick = { select(choice.key) }) { RadioButton(selected == choice.key, { select(choice.key) }) } }
                if (options.isEmpty()) item("empty") { Text(row.emptyText, Modifier.padding(16.dp)) }
                item("library") { SettingLine("共享字体库", "导入、排序和删除字体", onClick = { onNavigate(); controller.navigator.open("font-library") }) { Chevron() } }
            }
            else -> Unit
        }
    }
}

@Composable
internal fun ReorderHandle() {
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.surfaceContainer),
            contentAlignment = Alignment.Center) { ConsoleIcon("drag", Modifier.size(20.dp), MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
internal fun ReorderEditor(row: SettingsRow, controller: SettingsController, inline: Boolean = false) {
    val entry = LocalSettingsEntry.current
    var highlight by remember { mutableStateOf<String?>(null) }
    val choices: List<SettingsChoice>
    val orderKey: String
    when (row) {
        is SettingsRow.OrderedChoice -> { choices = row.choices; orderKey = row.orderKey }
        is SettingsRow.ParagraphOrder -> { choices = row.choices; orderKey = row.orderKey }
        else -> return
    }
    val valid = choices.map { it.key }
    val raw = controller.string(orderKey)
    val order = if (row is SettingsRow.ParagraphOrder) row.normalize(raw) else (raw.split(',').map(String::trim).filter { it in valid } + valid).distinct()
    val focus = if (inline) entry.focusChoice else entry.focusRow
    LaunchedEffect(focus) {
        val target = focus ?: return@LaunchedEffect
        val choice = choices.firstOrNull { it.key == target || it.title == target }?.key ?: return@LaunchedEffect
        if (!inline) entry.scroll.animateScrollToItem(order.indexOf(choice).coerceAtLeast(0) + if (row is SettingsRow.OrderedChoice) 2 else 1)
        highlight = choice
        delay(1700)
        highlight = null
        if (inline) entry.focusChoice = null else entry.focusRow = null
    }
    fun changed() { if (row is SettingsRow.OrderedChoice) row.onChanged?.run(controller.scope); controller.refreshValues() }
    val dragHost = LocalSettingsDrag.current
    val dragGroup = rememberDragGroup(row.id, order.map { id -> DragItem(id, choices.first { it.key == id }.title) }) { next ->
        val edit = SettingsRuntime.prefs(controller.activity).edit().putString(orderKey, next.joinToString(","))
        if (row is SettingsRow.OrderedChoice) edit.putBoolean(row.enabledKey, true)
        edit.commit().also { success -> if (success) changed() else controller.notify("排序保存失败") }
    }
    val previewHidden = when (row) {
        is SettingsRow.OrderedChoice -> readSettingsCsvSelection(controller.string(row.hiddenKey), valid).also { if (it.size >= valid.size) it.clear() }
        is SettingsRow.ParagraphOrder -> if (controller.boolean(row.copyEnabledKey)) emptySet() else setOf(row.copyChoiceKey)
        else -> emptySet()
    }
    val description: @Composable () -> Unit = {
        Column {
            ReorderPreview(choices, dragHost.preview(dragGroup, order), previewHidden, row !is SettingsRow.OrderedChoice || controller.boolean(row.enabledKey), dragHost.activeKey(dragGroup))
            SemanticText((if (inline) "" else row.summary.orEmpty() + "\n") + "长按条目拖动排序，松手保存；无障碍菜单支持上移、下移。", Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
    }
    val enabled: @Composable () -> Unit = {
        if (row is SettingsRow.OrderedChoice) SettingLine("启用自定义", onClick = { controller.putBoolean(row.enabledKey, !controller.boolean(row.enabledKey)); changed() }) {
            Switch(controller.boolean(row.enabledKey), { controller.putBoolean(row.enabledKey, it); changed() })
        }
    }
    val itemContent: @Composable (String, Modifier) -> Unit = { key, modifier ->
        val choice = choices.first { it.key == key }
        val requester = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
        LaunchedEffect(highlight == key) {
            if (inline && highlight == key) {
                delay(320)
                requester.bringIntoView()
            }
        }
        DragRow(dragGroup, key, modifier.bringIntoViewRequester(requester), trailingExclusion = if (row is SettingsRow.OrderedChoice) 64.dp else 0.dp) {
            Row(Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .settingsFocusHighlight(highlight == key),
                verticalAlignment = Alignment.CenterVertically) {
                ReorderHandle()
                Box(Modifier.weight(1f)) {
                    if (row is SettingsRow.OrderedChoice) {
                        val hidden = readSettingsCsvSelection(controller.string(row.hiddenKey), valid).also { if (it.size >= valid.size) it.clear() }
                        SettingLine(choice.title, choice.summary) {
                            Switch(key !in hidden, { visible ->
                                val next = updateSettingsCsvSelection(hidden.joinToString(","), valid, key = key, selected = !visible)
                                if (next.keys.size >= valid.size) controller.notify("至少保留一项")
                                else if (SettingsRuntime.prefs(controller.activity).edit().putString(row.hiddenKey, next.serialized).putBoolean(row.enabledKey, true).commit()) changed()
                                else controller.notify("保存失败")
                            })
                        }
                    } else {
                        row as SettingsRow.ParagraphOrder
                        SettingLine(choice.title, if (key == row.copyChoiceKey && !controller.boolean(row.copyEnabledKey)) "复制未开启，仍可调整位置" else choice.summary)
                    }
                }
            }
        }
    }
    if (inline) Column {
        description()
        enabled()
        order.forEach { choice -> key(choice) { itemContent(choice, Modifier) } }
    } else LazyColumn(state = entry.scroll, contentPadding = PaddingValues(12.dp)) {
        item("description") { description() }
        if (row is SettingsRow.OrderedChoice) item("enabled") { enabled() }
        items(order, key = { it }) { key -> itemContent(key, Modifier) }
    }
}

@Composable
private fun DocumentEditor(row: SettingsRow.DocumentEditor, controller: SettingsController) {
    var state by remember { mutableStateOf(row.controller.state(controller.activity)) }
    val editor = remember(row.id) { TextFieldState(state.text) }
    val editResetRevision = remember(row.id) { controller.resetRevision.intValue }
    var busy by remember { mutableStateOf(false) }
    var externalConfirmation by remember { mutableStateOf(false) }
    val sink = remember(row.id) { SettingsResultSink<SettingsDocumentActionResult>() }
    fun syncDraft() {
        // Reset can pop this editor while its exit animation still owns pending input effects.
        if (controller.resetRevision.intValue != editResetRevision) return
        val text = editor.text.toString()
        if (text != state.text) {
            row.controller.updateDraft(text)
            state = state.copy(text = text, dirty = true, errorMessage = null)
        }
    }
    LaunchedEffect(editor) { snapshotFlow { editor.text.toString() }.collect { syncDraft() } }
    // The controller owns the draft across screens; composing never reloads over unsaved input.
    DisposableEffect(row.id) {
        sink.consumer = { result ->
            busy = false
            // Flush input that arrived after the worker finished but before its main-thread reply.
            syncDraft()
            state = row.controller.state(controller.activity)
            if (editor.text.toString() != state.text) editor.edit {
                val previous = selection
                replace(0, length, state.text)
                selection = TextRange(previous.start.coerceIn(0, length), previous.end.coerceIn(0, length))
            }
            controller.notify(result.message)
        }
        onDispose { syncDraft(); sink.close() }
    }
    fun external() { syncDraft(); busy = true; row.controller.openExternalEditor(controller.activity, sink::deliver) }
    // Keep the editor frame fixed. Only its inner text viewport handles IME insets.
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(state.sourceLabel, Modifier.fillMaxWidth().combinedClickable(
            enabled = state.sourcePath != null, onClick = {}, onLongClickLabel = "复制文件路径",
            onLongClick = {
                state.sourcePath?.let { path ->
                    controller.activity.getSystemService(ClipboardManager::class.java)
                        ?.let { clipboard ->
                            clipboard.setPrimaryClip(ClipData.newPlainText("规则文件路径", path))
                            controller.notify("已复制文件路径")
                        } ?: controller.notify("无法访问剪贴板")
                }
            },
        ), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (state.dirty) "草稿未保存" else "${state.ruleCount} 条规则", Modifier.weight(1f), color = semanticColor(if (state.dirty) SemanticTone.Warning else SemanticTone.Info), style = MaterialTheme.typography.labelMedium)
            ConsoleActionButton("外部编辑", { syncDraft(); if (state.dirty) externalConfirmation = true else external() }, enabled = !busy, tone = SemanticTone.Accent)
            Spacer(Modifier.width(8.dp))
            // Busy covers both actions; keep the label and row geometry stable for either one.
            ConsoleActionButton("保存", { syncDraft(); busy = true; row.controller.save(controller.activity, sink::deliver) }, enabled = !busy, tone = SemanticTone.Info)
        }
        state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Text("保存后校验规则；无效内容不会替换正在生效的规则。", style = MaterialTheme.typography.bodySmall)
        ConsoleDocumentField(editor, Modifier.fillMaxWidth().weight(1f).padding(bottom = 12.dp),
            enabled = !busy, syntax = rememberYamlHighlight(editor.text.toString()))
    }
    if (externalConfirmation) ConsoleConfirmationDialog("打开外部编辑器？", "当前草稿将交给外部编辑器。返回后会校验修改，并更新规则文件。", "继续",
        onConfirm = { externalConfirmation = false; external() }, onDismiss = { externalConfirmation = false })
}
