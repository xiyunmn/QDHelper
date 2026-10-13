package com.xiyunmn.qdhelper.settings.ui.compose.editor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xiyunmn.qdhelper.settings.api.SettingsChoice
import com.xiyunmn.qdhelper.settings.ui.compose.component.*

/** A schematic of saved order and visibility, never an imitation of host UI. */
@Composable
internal fun ReorderPreview(
    choices: List<SettingsChoice>, order: List<String>, hidden: Set<String>,
    enabled: Boolean, active: String?,
) {
    val byKey = choices.associateBy { it.key }
    val visible = order.filterNot { it in hidden }
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("排列预览", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                if (!enabled) SemanticBadge("自定义未启用", SemanticTone.Warning)
                else Text("${visible.size} 项显示", style = MaterialTheme.typography.labelSmall, color = semanticColor(SemanticTone.Info))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                visible.forEachIndexed { index, id -> key(id) {
                    val accent = semanticColor(SemanticTone.Info)
                    Surface(shape = RoundedCornerShape(9.dp),
                        color = if (id == active) MaterialTheme.colorScheme.primaryContainer else accent.copy(alpha = .09f)) {
                        Row(Modifier.padding(horizontal = 9.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${index + 1}", style = MaterialTheme.typography.labelMedium, color = accent)
                            Text(byKey.getValue(id).title, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                } }
            }
            if (hidden.isNotEmpty()) Text(
                "未显示：" + order.filter { it in hidden }.joinToString(" · ") { byKey.getValue(it).title },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("仅示意排列顺序；随下方调整实时更新。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
