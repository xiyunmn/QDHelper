package com.xiyunmn.qdhelper.settings.ui.compose.editor

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.xiyunmn.qdhelper.settings.ui.compose.navigation.LocalSettingsEntry
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.xiyunmn.qdhelper.settings.api.*
import com.xiyunmn.qdhelper.settings.ui.compose.component.*
import com.xiyunmn.qdhelper.settings.ui.compose.state.SettingsController
import com.xiyunmn.qdhelper.settings.ui.compose.state.SettingsResultSink
import kotlinx.coroutines.delay

@Composable
internal fun SettingsToolScreen(tool: SettingsToolController, controller: SettingsController) {
    val entry = LocalSettingsEntry.current
    var state by remember(tool) { mutableStateOf(tool.read(controller.activity.applicationContext)) }
    val fields = remember(tool) { mutableStateMapOf<String, String>().apply { state.fields.forEach { put(it.id, it.value) } } }
    var busy by remember { mutableStateOf(false) }
    var full by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<SettingsToolCommand?>(null) }
    val sink = remember { SettingsResultSink<SettingsToolResult>() }
    var request by remember { mutableStateOf<AutoCloseable?>(null) }
    fun copy() {
        val text = (if (full) state.fullText else state.text).orEmpty() + state.entries.joinToString("\n\n", prefix = if (state.entries.isEmpty()) "" else "\n\n") { it.title + "\n" + it.detail }
        controller.activity.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("QDHelper", text))
        controller.notify(if (full) "已复制完整信息" else "已复制")
    }
    fun requestCopy() {
        if (full) confirm = SettingsToolCommand("copy", "复制完整信息", "完整账号、设备身份与宿主诊断会进入系统剪贴板，请只在本机调试时使用。")
        else copy()
    }
    fun execute(command: String) {
        if (command == "copy") { copy(); return }
        if (command == "clear-draft") { fields.keys.toList().forEach { fields[it] = "" }; return }
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
            if (result.message.isNotBlank()) controller.notify(result.message)
            controller.refreshValues()
            if (result.close) controller.navigator.back()
        }
        onDispose { sink.close(); request?.close(); request = null }
    }
    LaunchedEffect(tool) { tool.initialCommand?.let(::execute) }
    LaunchedEffect(tool, busy) {
        if (tool.refreshIntervalMillis > 0 && !busy) while (true) {
            delay(tool.refreshIntervalMillis); state = tool.read(controller.activity.applicationContext)
        }
    }
    val fixedActions = entry.editor == "diagnostics.cookie-identity"
    Column(Modifier.fillMaxSize().imePadding()) {
        ScrollToTopContainer(entry.scroll, Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), state = entry.scroll, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 80.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (busy) item("busy") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                item("text") {
                    val text = if (full) state.fullText.orEmpty() else state.text
                    if (text.isNotBlank()) ConsoleSurface {
                        SettingsSelectionContainer {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(text.substringBefore('\n'), color = semanticColor(state.status.tone()), style = MaterialTheme.typography.titleMedium)
                                text.substringAfter('\n', "").takeIf(String::isNotBlank)?.let { SemanticText(it) }
                            }
                        }
                    }
                }
                state.error?.let { error -> item("error") { Text(error, color = semanticColor(SemanticTone.Error)) } }
                items(state.fields, key = { "field:${it.id}" }) { field ->
                    ConsoleTextField(fields[field.id].orEmpty(), { fields[field.id] = it }, Modifier.fillMaxWidth(), label = { Text(field.label) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = if (field.numeric) KeyboardType.Number else KeyboardType.Text), enabled = !busy)
                }
                if (!fixedActions || state.commands.isNotEmpty()) item("commands") {
                    val actions = buildList {
                        if (!fixedActions) {
                            add(SettingsToolCommand("copy-current", "复制当前"))
                            if (state.fullText != null) add(SettingsToolCommand("toggle-full", if (full) "显示脱敏" else "显示完整"))
                        }
                        addAll(state.commands)
                    }
                    ConsoleActionGrid(actions.size) { index, modifier ->
                        val command = actions[index]
                        ConsoleActionButton(command.label, {
                            when (command.id) {
                                "copy-current" -> requestCopy()
                                "toggle-full" -> full = !full
                                else -> if (command.confirmation != null) confirm = command else execute(command.id)
                            }
                        }, modifier, enabled = !busy, tone = when (command.id) {
                            "copy-current" -> SemanticTone.Info
                            "toggle-full" -> SemanticTone.Accent
                            "clear" -> SemanticTone.Error
                            else -> null
                        })
                    }
                }
                items(state.entries, key = { "entry:${it.id}" }) { item -> ConsoleSurface {
                    SettingsSelectionContainer {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(item.title, color = semanticColor(item.status.tone()), style = MaterialTheme.typography.titleSmall)
                            SemanticText(item.detail, emphasizedText = item.emphasizedText)
                        }
                    }
                } }
            }
        }
        if (fixedActions) Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shadowElevation = 4.dp,
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ConsoleActionButton(if (full) "显示脱敏" else "显示完整", { full = !full },
                    Modifier.weight(1f).heightIn(min = 48.dp), enabled = !busy && state.fullText != null,
                    tone = SemanticTone.Accent, icon = if (full) "mask" else "reveal")
                ConsoleActionButton("复制当前", ::requestCopy, Modifier.weight(1f).heightIn(min = 48.dp),
                    enabled = !busy && state.text.isNotBlank(), tone = SemanticTone.Info, icon = "copy")
            }
        }
    }
    confirm?.let { command -> ConsoleConfirmationDialog(command.label, command.confirmation.orEmpty(), command.label,
        onConfirm = { confirm = null; execute(command.id) }, onDismiss = { confirm = null }, tone = toolConfirmationTone(command.id)) }
}

private fun SettingsToolStatus.tone() = when (this) {
    SettingsToolStatus.Neutral -> SemanticTone.Info
    SettingsToolStatus.Success -> SemanticTone.Success
    SettingsToolStatus.Warning -> SemanticTone.Warning
    SettingsToolStatus.Error -> SemanticTone.Error
}
