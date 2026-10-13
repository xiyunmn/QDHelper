package com.xiyunmn.qdhelper.settings.ui.compose.row

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.xiyunmn.qdhelper.settings.api.SettingsRow
import com.xiyunmn.qdhelper.settings.ui.compose.component.*
import com.xiyunmn.qdhelper.settings.ui.compose.editor.*
import com.xiyunmn.qdhelper.settings.ui.compose.navigation.LocalSettingsEntry
import com.xiyunmn.qdhelper.settings.ui.compose.presentation.SettingsApplyPolicy
import com.xiyunmn.qdhelper.settings.ui.compose.state.SettingsController
import kotlin.math.roundToInt

@Composable
internal fun SettingsRowRenderer(row: SettingsRow, controller: SettingsController, edit: (SettingsRow) -> Unit = controller::openRow) {
    val dependency = controller.dependency(row)
    val enabled = dependency == null
    if (row is SettingsRow.Action && row.id == "maintenance.clear-host-data") {
        ConfirmedToolAction(row, controller)
        return
    }
    val meta = controller.catalog.metadata.getValue(row.id)
    val mode = SettingsApplyPolicy.mode(row)
    val badge = listOf(meta.risk.label, if (mode == com.xiyunmn.qdhelper.settings.ui.compose.presentation.ApplyMode.HostRestart) mode.label else "").filter(String::isNotBlank).joinToString(" · ")
    val riskColor = if (meta.risk in setOf(com.xiyunmn.qdhelper.settings.ui.compose.presentation.SettingRisk.HighRisk, com.xiyunmn.qdhelper.settings.ui.compose.presentation.SettingRisk.Attention, com.xiyunmn.qdhelper.settings.ui.compose.presentation.SettingRisk.Experimental)) {
        semanticColor(SemanticTone.Warning)
    } else if (meta.risk == com.xiyunmn.qdhelper.settings.ui.compose.presentation.SettingRisk.Diagnostic) semanticColor(SemanticTone.Info)
    else if (mode == com.xiyunmn.qdhelper.settings.ui.compose.presentation.ApplyMode.HostRestart) semanticColor(SemanticTone.Warning) else null
    if (row is SettingsRow.OrderedChoice || row is SettingsRow.ParagraphOrder) {
        val entry = LocalSettingsEntry.current
        val expanded = entry.expanded[row.id] == true
        LaunchedEffect(entry.focusRow) { if (entry.focusRow == row.id) entry.expanded[row.id] = true }
        Column {
            SettingLine(row.title, dependency ?: row.summary, enabled, badge,
                onClick = { entry.expanded[row.id] = !expanded }, badgeColor = riskColor) {
                ConsoleIcon(if (expanded) "up" else "down", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (expanded) ReorderEditor(row, controller, inline = true)
        }
        return
    }
    if (row is SettingsRow.TextInput && row.id == "network-identity.dns-stack") {
        Column {
            SettingLine(row.title, dependency ?: row.summary, enabled, badge, badgeColor = riskColor)
            FlowRow(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("1" to "IPv4", "2" to "IPv6", "3" to "双栈").forEach { (value, label) ->
                    FilterChip(controller.string(row.key) == value, {
                        if (controller.putString(row.key, value)) row.onChanged?.run(controller.scope, value)
                    }, { Text(label) }, enabled = enabled)
                }
            }
        }
        return
    }
    if (row is SettingsRow.TextInput && row.singleLine || row is SettingsRow.SessionTextInput) {
        Column { InlineTextSetting(row, controller, enabled, dependency ?: row.summary, badge, riskColor) }
        return
    }
    if (row is SettingsRow.Action && row.tool != null && row.id in setOf("book-shield.word-range", "maintenance.user-lookup")) {
        Column { InlineToolSetting(row, controller, enabled, dependency ?: row.summary, badge, riskColor) }
        return
    }
    if (row is SettingsRow.SingleChoice && row.choices.size <= 3) {
        Column {
            SettingLine(row.title, dependency ?: row.summary, enabled, badge, badgeColor = riskColor)
            InlineSingleChoice(row, controller, enabled)
        }
        return
    }
    if (row is SettingsRow.MultiChoice && row.id == "book-shield.locations") {
        Column { InlineMultiChoice(row, controller, enabled) }
        return
    }
    when (row) {
        is SettingsRow.Toggle -> SettingLine(row.title, dependency ?: row.summary, enabled, badge,
            onClick = { controller.toggle(row, !controller.boolean(row.key)) }, badgeColor = riskColor) {
            Switch(controller.boolean(row.key), { controller.toggle(row, it) }, enabled = enabled)
        }
        is SettingsRow.Slider -> {
            val raw = controller.string(row.key)
            val stored = normalizedSliderStorage(row, raw)
            val resetRevision = controller.resetRevision.intValue
            val editor = remember(row.id, raw, resetRevision, enabled) { SliderEditState(row, raw, resetRevision) }
            val displayRevision = controller.revision.intValue
            val displayValue = remember(row, editor.value, displayRevision) { row.valueFormatter(editor.value.toDouble()) }
            LaunchedEffect(row.id, raw) { if (raw != stored) controller.putString(row.key, stored) }
            Column {
                SettingLine(row.title, dependency ?: row.summary, enabled, badge, badgeColor = riskColor)
                Text(displayValue, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                Slider(editor.value.toFloat(), editor::preview, Modifier.fillMaxWidth().padding(horizontal = 16.dp), enabled,
                    valueRange = row.minValue.toFloat()..row.maxValue.toFloat(),
                    steps = (((row.maxValue-row.minValue)/row.step).roundToInt()-1).coerceAtLeast(0),
                    onValueChangeFinished = {
                        if (!controller.closed) editor.finish(
                            current = controller.string(row.key),
                            currentResetRevision = controller.resetRevision.intValue,
                            enabled = controller.dependency(row) == null,
                            save = { controller.putString(row.key, it) },
                            onSaved = { row.onChanged?.run(controller.scope, it) },
                            onCommitted = { row.onCommitted?.run(controller.scope, it) },
                        )
                    })
            }
        }
        is SettingsRow.Hint -> SemanticText(row.text, Modifier.padding(16.dp))
        else -> {
            val revision = controller.revision.intValue
            val summary = when (row) {
                is SettingsRow.DynamicChoice -> remember(revision) { row.controller.summary(controller.activity) }
                is SettingsRow.Action -> remember(revision) { row.summaryProvider?.summary(controller.activity) ?: row.summary }
                is SettingsRow.TextInput -> controller.string(row.key).takeIf(String::isNotBlank)?.let { if (row.id.contains("qimei") || row.id.endsWith("guid")) "已设置 · 点击编辑" else it } ?: row.summary
                is SettingsRow.SingleChoice -> row.choices.firstOrNull { it.key == controller.string(row.key) }?.title ?: row.summary
                else -> row.summary
            }
            SettingLine(row.title, dependency ?: summary, enabled, badge, onClick = { edit(row) }, badgeColor = riskColor,
                leading = if (row.id == "book-shield.stats") { { IconTile(row.id) } } else null) { Chevron() }
        }
    }
}
