package com.xiyunmn.qdhelper.settings.ui.compose.editor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiyunmn.qdhelper.settings.api.SettingsManagedListItem
import com.xiyunmn.qdhelper.settings.api.SettingsFontPreview
import com.xiyunmn.qdhelper.settings.ui.compose.component.ConsoleIcon
import com.xiyunmn.qdhelper.settings.ui.compose.component.ConsoleSurface
import com.xiyunmn.qdhelper.settings.ui.compose.component.SemanticBadge
import com.xiyunmn.qdhelper.settings.ui.compose.component.SemanticTone
import com.xiyunmn.qdhelper.settings.ui.compose.component.semanticColor

@Composable
internal fun FontLibraryItem(item: SettingsManagedListItem, preview: SettingsFontPreview?,
    managing: Boolean = false, selected: Boolean = false, enabled: Boolean = true,
    onSelected: (Boolean) -> Unit = {}, onDelete: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val deleteColor = semanticColor(SemanticTone.Error)
    val family = preview?.typeface?.let { remember(it) { FontFamily(it) } } ?: FontFamily.Default
    ConsoleSurface {
        Row(Modifier.fillMaxWidth()
            .then(if (managing) Modifier.background(colors.primary.copy(alpha = if (selected) .08f else 0f))
                .toggleable(selected, enabled = enabled, role = Role.Checkbox, onValueChange = onSelected) else Modifier)
            .heightIn(min = 64.dp)
            .padding(start = 8.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            // The row owns long-press sorting; the grip does not need a separate button gutter.
            Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                if (managing) Checkbox(selected, onCheckedChange = null, enabled = enabled, modifier = Modifier.size(20.dp))
                else ConsoleIcon("drag", Modifier.size(16.dp), colors.onSurfaceVariant)
            }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(item.title, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal, fontFamily = family,
                    color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                item.summary?.takeIf(String::isNotBlank)?.let { filename ->
                    Text(filename, fontSize = 12.sp, lineHeight = 17.sp, color = colors.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                (item.note ?: preview?.message)?.takeIf(String::isNotBlank)?.let { note ->
                    Text(note, fontSize = 12.sp, lineHeight = 17.sp, color = colors.onSurfaceVariant)
                }
                if (item.active) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    item.badge.orEmpty().split(" / ").filter(String::isNotBlank)
                        .ifEmpty { listOf("使用中") }.forEach { role ->
                            SemanticBadge(role, when (role) {
                                "阅读" -> SemanticTone.Info
                                "强调" -> SemanticTone.Warning
                                else -> SemanticTone.Success
                            })
                        }
                }
            }
            if (!managing) {
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = onDelete, enabled = enabled, modifier = Modifier.size(48.dp)
                    .semantics { contentDescription = "删除字体：${item.title}" }) {
                    Surface(Modifier.size(36.dp), shape = RoundedCornerShape(10.dp),
                        color = deleteColor.copy(alpha = .12f)) {
                        Box(contentAlignment = Alignment.Center) {
                            ConsoleIcon("delete", Modifier.size(18.dp), deleteColor)
                        }
                    }
                }
            }
        }
    }
}
