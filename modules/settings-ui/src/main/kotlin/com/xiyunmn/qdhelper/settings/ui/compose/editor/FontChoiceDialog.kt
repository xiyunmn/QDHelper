package com.xiyunmn.qdhelper.settings.ui.compose.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiyunmn.qdhelper.settings.api.SettingsFontChoiceController
import com.xiyunmn.qdhelper.settings.api.SettingsFontPreview
import com.xiyunmn.qdhelper.settings.api.SettingsRow
import com.xiyunmn.qdhelper.settings.ui.compose.state.SettingsController
import com.xiyunmn.qdhelper.shared.compose.ModuleFloatingDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun FontChoiceDialog(row: SettingsRow.DynamicChoice, fonts: SettingsFontChoiceController,
    controller: SettingsController, onDismiss: () -> Unit) {
    val revision = controller.revision.intValue
    val (options, selected) = remember(fonts, revision) {
        fonts.items(controller.activity) to fonts.selectedKey(controller.activity)
    }
    val context = controller.activity.applicationContext
    // Keep the last sample until its replacement is ready. Unrelated preference revisions
    // must not clear it or restart loading, including the observer's second save notification.
    val preview by produceState<SettingsFontPreview?>(null, fonts, selected) {
        value = withContext(Dispatchers.IO) {
            runCatching { fonts.preview(context, selected) }
                .getOrElse { SettingsFontPreview(message = "字体无法加载，请返回字体库检查文件") }
        }
    }
    val select: (String?) -> Unit = { choice ->
        val result = fonts.select(controller.activity, choice)
        if (!result.success) controller.notify(result.message.ifBlank { "字体保存失败" })
        controller.refreshValues()
    }
    ModuleFloatingDialog(onDismissRequest = onDismiss) {
        BoxWithConstraints(Modifier.padding(8.dp), contentAlignment = Alignment.Center) {
            val colors = MaterialTheme.colorScheme
            Surface(Modifier.widthIn(max = 480.dp).fillMaxWidth().heightIn(max = minOf(maxHeight * .82f, 640.dp)),
                shape = RoundedCornerShape(24.dp), color = colors.surface,
                border = BorderStroke(.5.dp, colors.outlineVariant.copy(alpha = .35f)), shadowElevation = 8.dp) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(row.title, Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                            color = colors.onSurface)
                        TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurfaceVariant)) { Text("关闭") }
                    }
                    LazyColumn(Modifier.fillMaxWidth().weight(1f, false).selectableGroup(),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
                        item("default") {
                            FontOption(fonts.defaultTitle, fonts.defaultSummary, selected == null) { select(null) }
                        }
                        items(options, key = { it.key }) { option ->
                            FontOption(option.title, option.summary.orEmpty(), selected == option.key) { select(option.key) }
                        }
                        if (options.isEmpty()) item("empty") {
                            Text("暂无导入字体，请返回字体库添加", Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                                fontSize = 13.sp, color = colors.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = colors.outlineVariant.copy(alpha = .4f))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("山河远阔，人间烟火", fontSize = 20.sp, lineHeight = 28.sp, color = colors.onSurface,
                                fontFamily = preview?.typeface?.let { remember(it) { FontFamily(it) } } ?: FontFamily.Default)
                            Text("字体样张", fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Normal,
                                color = colors.onSurfaceVariant.copy(alpha = .72f))
                            preview?.message?.let { Text(it, fontSize = 11.sp, lineHeight = 15.sp, color = colors.onSurfaceVariant) }
                        }
                        FilledTonalButton(onClick = onDismiss, shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(containerColor = colors.primary.copy(alpha = .10f), contentColor = colors.primary),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) { Text("完成") }
                    }
                }
            }
        }
    }
}

@Composable
private fun FontOption(title: String, filename: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(12.dp), color = if (selected) colors.primary.copy(alpha = .08f) else Color.Transparent) {
        Row(Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 52.dp).padding(start = 10.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontSize = 15.sp, lineHeight = 21.sp, color = colors.onSurface,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(filename, fontSize = 11.sp, lineHeight = 15.sp, color = colors.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            RadioButton(selected, onClick = null, modifier = Modifier.size(20.dp))
        }
    }
}
