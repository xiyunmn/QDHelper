package com.xiyunmn.qdhelper.settings.ui.compose.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xiyunmn.qdhelper.shared.compose.ModuleFloatingDialog
import com.xiyunmn.qdhelper.settings.api.SettingsRow
import com.xiyunmn.qdhelper.settings.api.SettingsFontChoiceController
import com.xiyunmn.qdhelper.settings.ui.compose.component.ConsoleIconButton
import com.xiyunmn.qdhelper.settings.ui.compose.state.SettingsController

/** Compact choices float above the current page; only the option list scrolls. */
@Composable
internal fun SettingsChoiceDialog(row: SettingsRow, controller: SettingsController, onDismiss: () -> Unit) {
    if (row is SettingsRow.DynamicChoice && row.controller is SettingsFontChoiceController) {
        FontChoiceDialog(row, row.controller as SettingsFontChoiceController, controller, onDismiss)
        return
    }
    ModuleFloatingDialog(onDismissRequest = onDismiss) {
        BoxWithConstraints(Modifier.padding(8.dp), contentAlignment = Alignment.Center) {
            Surface(
                Modifier.widthIn(max = 480.dp).fillMaxWidth().heightIn(max = minOf(maxHeight * .85f, 640.dp)),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 0.dp,
                shadowElevation = 12.dp,
                border = BorderStroke(.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f)),
            ) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(row.title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        ConsoleIconButton("close", "关闭", onDismiss)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f))
                    ChoiceEditor(row, controller, onNavigate = onDismiss, modifier = Modifier.weight(1f, fill = false))
                }
            }
        }
    }
}
