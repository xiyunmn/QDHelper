package com.xiyunmn.qdhelper.settings.ui.compose.editor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.xiyunmn.qdhelper.settings.api.*
import com.xiyunmn.qdhelper.settings.ui.compose.component.*
import com.xiyunmn.qdhelper.settings.ui.compose.navigation.LocalSettingsEntry
import com.xiyunmn.qdhelper.settings.ui.compose.state.SettingsController
import com.xiyunmn.qdhelper.settings.ui.compose.state.SettingsResultSink
import com.xiyunmn.qdhelper.settings.ui.readSettingsCsvSelection
import com.xiyunmn.qdhelper.settings.ui.updateSettingsCsvSelection

@Composable
internal fun InlineTextSetting(row: SettingsRow, controller: SettingsController, enabled: Boolean, summary: String?, badge: String?, badgeColor: androidx.compose.ui.graphics.Color?, alwaysExpanded: Boolean = false, compact: Boolean = false) {
    val entry = LocalSettingsEntry.current
    LaunchedEffect(entry.focusRow) { if (entry.focusRow == row.id) entry.expanded[row.id] = true }
    val expanded = alwaysExpanded || entry.expanded[row.id] == true || entry.focusRow == row.id
    val external = when (row) {
        is SettingsRow.TextInput -> controller.string(row.key)
        is SettingsRow.SessionTextInput -> { controller.revision.intValue; row.valueProvider.value(controller.activity) }
        else -> return
    }
    if (!compact) SettingLine(row.title, summary, enabled, badge, if (alwaysExpanded) null else { { entry.expanded[row.id] = !expanded } }, badgeColor) {
        if (!alwaysExpanded) ConsoleIcon(if (expanded) "up" else "down", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (expanded) {
        var text by remember(row.id, controller.resetRevision.intValue, external) { mutableStateOf(external) }
        var invalid by remember(row.id, controller.resetRevision.intValue, external) { mutableStateOf(false) }
        val single = when (row) { is SettingsRow.TextInput -> row.singleLine; is SettingsRow.SessionTextInput -> row.singleLine; else -> true }
        val hint = when (row) { is SettingsRow.TextInput -> row.hint; is SettingsRow.SessionTextInput -> row.hint; else -> "" }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
            ConsoleTextField(text, { value ->
                text = value
                val validator = when (row) { is SettingsRow.TextInput -> row.validator; is SettingsRow.SessionTextInput -> row.validator; else -> null }
                invalid = validator?.invoke(value) == false
                if (!invalid) when (row) {
                    is SettingsRow.TextInput -> if (controller.putString(row.key, value)) row.onChanged?.run(controller.scope, value)
                    is SettingsRow.SessionTextInput -> row.onChanged.run(controller.scope, value)
                    else -> Unit
                }
            }, Modifier.fillMaxWidth(), enabled = enabled, singleLine = single, minLines = if (single) 1 else 3, maxLines = if (single) 1 else 6,
                placeholder = { Text(hint) }, isError = invalid,
                label = if (compact) { { Text(row.title) } } else null,
                supportingText = if (invalid || !compact) { { Text(if (invalid) "格式不正确，尚未保存" else "修改自动保存") } } else null)
        }
    }
}

@Composable
internal fun InlineSingleChoice(row: SettingsRow.SingleChoice, controller: SettingsController, enabled: Boolean) {
    val selected = controller.string(row.key).ifBlank { row.defaultKey }
    FlowRow(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        row.choices.forEach { choice ->
            FilterChip(selected == choice.key, { controller.putString(row.key, choice.key) }, { Text(choice.title) }, enabled = enabled)
        }
    }
}

@Composable
internal fun InlineMultiChoice(row: SettingsRow.MultiChoice, controller: SettingsController, enabled: Boolean) {
    val entry = LocalSettingsEntry.current
    val expanded = entry.expanded[row.id] == true
    val valid = remember(row) { row.choices.map { it.key } }
    val raw = controller.string(row.key)
    val selected = readSettingsCsvSelection(raw, valid, row.defaultSelected)
    val visible = if (expanded) row.choices else row.choices.take(4)
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text("已选择 ${selected.size} / ${valid.size} 项", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            visible.forEach { choice ->
                FilterChip(choice.key in selected, {
                    val next = updateSettingsCsvSelection(raw, valid, row.defaultSelected, choice.key, choice.key !in selected)
                    controller.putString(row.key, next.serialized)
                }, { Text(choice.title, style = MaterialTheme.typography.labelMedium) }, enabled = enabled)
            }
        }
        if (row.choices.size > 4 && !expanded) TextButton({ entry.expanded[row.id] = true }, Modifier.fillMaxWidth()) {
            Text("更多生效位置（共 ${valid.size} 项）")
        }
    }
}

/** Execute a single feature command only after its existing confirmation has been accepted. */
@Composable
internal fun ConfirmedToolAction(row: SettingsRow.Action, controller: SettingsController) {
    val tool = row.tool ?: return
    val command = remember(tool) { tool.read(controller.activity.applicationContext).commands.single() }
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val sink = remember(tool) { SettingsResultSink<SettingsToolResult>() }
    var request by remember { mutableStateOf<AutoCloseable?>(null) }
    DisposableEffect(tool) {
        sink.consumer = { result ->
            busy = false
            controller.refreshValues()
            controller.notify(result.state?.error ?: result.message.ifBlank { "操作已完成" })
        }
        onDispose { sink.close(); request?.close() }
    }
    SettingLine(row.title, row.summary, enabled = !busy, onClick = { confirm = true }) {
        ConsoleActionButton(if (busy) "清理中…" else "清理", { confirm = true }, enabled = !busy, tone = SemanticTone.Error)
    }
    if (confirm) ConsoleConfirmationDialog(
        onDismiss = { confirm = false },
        title = "确认保留登录态清数据？",
        text = command.confirmation.orEmpty(),
        confirmLabel = "确认清理", tone = SemanticTone.Error,
        onConfirm = {
                confirm = false
                busy = true
                request = runCatching { tool.execute(controller.activity, command.id, emptyMap(), sink::deliver) }.getOrElse {
                    busy = false
                    controller.notify("清理未启动：${it.javaClass.simpleName}")
                    null
                }
        },
    )
}

/** Short feature-owned forms remain in their page; callbacks never pop the navigation stack. */
@Composable
internal fun InlineToolSetting(row: SettingsRow.Action, controller: SettingsController, enabled: Boolean, summary: String?, badge: String?, badgeColor: androidx.compose.ui.graphics.Color?) {
    val entry = LocalSettingsEntry.current
    LaunchedEffect(entry.focusRow) { if (entry.focusRow == row.id) entry.expanded[row.id] = true }
    val alwaysExpanded = row.id == "maintenance.user-lookup"
    val expanded = alwaysExpanded || entry.expanded[row.id] == true || entry.focusRow == row.id
    SettingLine(row.title, summary, enabled, badge, if (alwaysExpanded) null else { { entry.expanded[row.id] = !expanded } }, badgeColor) {
        if (!alwaysExpanded) ConsoleIcon(if (expanded) "up" else "down", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (expanded) {
        val tool = row.tool ?: return
        var state by remember(tool, controller.resetRevision.intValue) { mutableStateOf(tool.read(controller.activity.applicationContext)) }
        val fields = remember(tool, controller.resetRevision.intValue) { mutableStateMapOf<String, String>().apply { state.fields.forEach { put(it.id, it.value) } } }
        var busy by remember { mutableStateOf(false) }
        val sink = remember { SettingsResultSink<SettingsToolResult>() }
        var request by remember { mutableStateOf<AutoCloseable?>(null) }
        var confirm by remember { mutableStateOf<SettingsToolCommand?>(null) }
        val focus = LocalFocusManager.current
        fun execute(command: String) {
            if (command == "clear-draft") { fields.keys.toList().forEach { fields[it] = "" }; return }
            focus.clearFocus()
            busy = true
            request?.close()
            request = runCatching { tool.execute(controller.activity, command, fields.toMap(), sink::deliver) }.getOrElse {
                busy = false; controller.notify("操作未完成：${it.javaClass.simpleName}"); null
            }
        }
        DisposableEffect(tool) {
            sink.consumer = { result ->
                busy = false
                state = result.state ?: tool.read(controller.activity.applicationContext)
                controller.refreshValues()
                if (result.message.isNotBlank()) controller.notify(result.message)
                if (result.close) { entry.focusRow = null; entry.expanded[row.id] = false }
            }
            onDispose { sink.close(); request?.close() }
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.text.isNotBlank()) Text(state.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.fields.forEach { field ->
                ConsoleTextField(fields[field.id].orEmpty(), { fields[field.id] = it }, Modifier.fillMaxWidth(),
                    label = { Text(field.label) }, singleLine = true, enabled = enabled && !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = if (field.numeric) KeyboardType.Number else KeyboardType.Text, imeAction = if (alwaysExpanded) ImeAction.Search else ImeAction.Default),
                    keyboardActions = KeyboardActions(onSearch = { if (enabled && !busy && alwaysExpanded) execute("open") }))
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            ConsoleActionGrid(state.commands.size) { index, modifier ->
                val command = state.commands[index]
                ConsoleActionButton(command.label, { if (command.confirmation != null) confirm = command else execute(command.id) }, modifier, enabled = enabled && !busy,
                    tone = if (command.id == "clear-draft") SemanticTone.Accent else SemanticTone.Info)
            }
        }
        confirm?.let { command -> ConsoleConfirmationDialog(command.label, command.confirmation.orEmpty(), command.label,
            onConfirm = { confirm = null; execute(command.id) }, onDismiss = { confirm = null }, tone = toolConfirmationTone(command.id)) }
    }
}
