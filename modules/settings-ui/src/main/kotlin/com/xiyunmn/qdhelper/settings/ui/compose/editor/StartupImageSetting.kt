package com.xiyunmn.qdhelper.settings.ui.compose.editor

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xiyunmn.qdhelper.settings.api.SettingsRow
import com.xiyunmn.qdhelper.settings.ui.compose.component.ConsoleIcon
import com.xiyunmn.qdhelper.settings.ui.compose.component.SettingLine
import com.xiyunmn.qdhelper.settings.ui.compose.navigation.LocalSettingsEntry
import com.xiyunmn.qdhelper.settings.ui.compose.state.SettingsController

@Composable
internal fun StartupImageSetting(row: SettingsRow.Toggle, inputs: List<SettingsRow.TextInput>, controller: SettingsController) {
    val entry = LocalSettingsEntry.current
    val enabled = controller.boolean(row.key)
    val located = entry.focusRow == row.id || inputs.any { it.id == entry.focusRow }
    val expanded = (entry.expanded[row.id] ?: enabled) || located
    LaunchedEffect(located) { if (located) entry.expanded[row.id] = true }
    val official = row.id == "startup.official-enabled"
    Column {
        SettingLine(
            if (official) row.title else "启动图直链",
            if (enabled) "已启用 · 修改后自动同步，下次冷启动生效" else "未启用 · 点击配置，可预先填写",
            onClick = { entry.expanded[row.id] = !expanded },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConsoleIcon(if (expanded) "up" else "down", Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                Switch(enabled, { checked ->
                    controller.toggle(row, checked)
                    if (checked) entry.expanded[row.id] = true
                })
            }
        }
        if (expanded) {
            inputs.forEach { input -> key(input.id) {
                InlineTextSetting(input, controller, enabled = true, summary = null, badge = null, badgeColor = null,
                    alwaysExpanded = true, compact = true)
            } }
            Text(if (official) "自动保存；Paper ID 可选，留空按图片直链生成。"
                else "自动保存；支持带图片扩展名的 http、https、file 链接。",
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
