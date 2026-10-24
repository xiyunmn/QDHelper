package com.xiyunmn.qdhelper.settings.ui.compose.row

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xiyunmn.qdhelper.settings.api.SettingsRow
import com.xiyunmn.qdhelper.settings.ui.compose.component.*
import com.xiyunmn.qdhelper.settings.ui.compose.navigation.LocalSettingsEntry
import com.xiyunmn.qdhelper.settings.ui.compose.state.SettingsController
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

internal val groupedFeatureRows = mapOf(
    "read_time.multiplier.enabled" to listOf("read_time.multiplier.value", "read_time.local_display.real",
        "read_time.report_audit", "read_time.cloud_probe"),
)

internal val groupedReadingNotes = setOf("read_time.hint")

@Composable
internal fun GroupedFeatureSetting(parent: SettingsRow, children: List<SettingsRow>, controller: SettingsController,
    edit: (SettingsRow) -> Unit) {
    Column {
        SettingsRowRenderer(parent, controller, edit)
        children.forEach { row -> key(row.id) {
            FeatureChildFocus(listOf(row.id)) { ReadingChildSetting(row, controller, edit) }
        } }
    }
}

@Composable
private fun ReadingChildSetting(row: SettingsRow, controller: SettingsController, edit: (SettingsRow) -> Unit) {
    when (row) {
        is SettingsRow.Slider -> SettingsChildSurface(bottomPadding = 6.dp) { CompactFeatureSetting(row, controller, edit) }
        is SettingsRow.Toggle, is SettingsRow.Action -> CompactFeatureSetting(row, controller, edit)
        else -> Box(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp)) { CompactFeatureSetting(row, controller, edit) }
    }
}

@Composable
private fun CompactFeatureSetting(row: SettingsRow, controller: SettingsController, edit: (SettingsRow) -> Unit) {
    val dependency = controller.dependency(row)
    val enabled = dependency == null
    when (row) {
        is SettingsRow.Slider -> CompactFeatureSlider(row, controller, enabled)
        is SettingsRow.Toggle -> ChildToggleSetting(row.title, controller.boolean(row.key),
            { controller.toggle(row, it) }, enabled = enabled, summary = dependency ?: row.summary)
        is SettingsRow.Action -> {
            val revision = controller.revision.intValue
            val summary = remember(row, revision) { row.summaryProvider?.summary(controller.activity) ?: row.summary }
            SettingsChildEntry(row.title, dependency ?: summary, enabled, onClick = { edit(row) })
        }
        else -> SettingsRowRenderer(row, controller, edit)
    }
}

@Composable
private fun CompactFeatureSlider(row: SettingsRow.Slider, controller: SettingsController, enabled: Boolean) {
    val raw = controller.string(row.key)
    val stored = normalizedSliderStorage(row, raw)
    val resetRevision = controller.resetRevision.intValue
    val editor = remember(row.id, raw, resetRevision, enabled) { SliderEditState(row, raw, resetRevision) }
    val revision = controller.revision.intValue
    val effective = remember(row, editor.value, revision) { row.valueFormatter(editor.value.toDouble()) }
    LaunchedEffect(row.id, raw) { if (raw != stored) controller.putString(row.key, stored) }
    Column {
        SettingLine(row.title, enabled = enabled, compact = true, trailing = {
            Text("${editor.value}×", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        })
        Slider(editor.value.toFloat(), editor::preview, Modifier.fillMaxWidth().padding(horizontal = 14.dp), enabled,
            valueRange = row.minValue.toFloat()..row.maxValue.toFloat(),
            steps = (((row.maxValue - row.minValue) / row.step).roundToInt() - 1).coerceAtLeast(0),
            onValueChangeFinished = {
                if (!controller.closed) editor.finish(
                    current = controller.string(row.key), currentResetRevision = controller.resetRevision.intValue,
                    enabled = controller.dependency(row) == null,
                    save = { controller.putString(row.key, it) },
                    onSaved = { row.onChanged?.run(controller.scope, it) },
                    onCommitted = { row.onCommitted?.run(controller.scope, it) },
                )
            })
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${row.minValue}×", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${row.maxValue}×", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(effective, Modifier.padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 10.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun GroupedReadingNotes(rows: List<SettingsRow.Hint>) {
    if (rows.isEmpty()) return
    val entry = LocalSettingsEntry.current
    val key = "notes:${rows.first().id}"
    val expanded = entry.expanded[key] == true
    LaunchedEffect(entry.focusRow) { if (rows.any { it.id == entry.focusRow }) entry.expanded[key] = true }
    FeatureChildFocus(rows.map { it.id }) {
        Column {
            SettingLine("使用说明", onClick = { entry.expanded[key] = !expanded }, trailing = {
                ConsoleIcon(if (expanded) "up" else "down", color = MaterialTheme.colorScheme.onSurfaceVariant)
            })
            if (expanded) rows.forEach { row -> Text(row.text,
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun FeatureChildFocus(ids: List<String>, content: @Composable () -> Unit) {
    val entry = LocalSettingsEntry.current
    val focus = entry.focusRow
    val focused = focus != null && focus in ids
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(focus) {
        if (focused) {
            delay(280)
            requester.bringIntoView()
            delay(1600)
            if (entry.focusRow == focus) entry.focusRow = null
        }
    }
    Box(Modifier.fillMaxWidth().bringIntoViewRequester(requester).settingsFocusHighlight(focused)) { content() }
}
