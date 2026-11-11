package com.xiyunmn.qdhelper.feature.contenttools.media

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiyunmn.qdhelper.shared.ui.UiColors
import kotlinx.coroutines.delay

private data class ExportPalette(val night: Boolean, val panel: Int, val card: Int, val text: Int,
    val muted: Int, val brand: Int, val divider: Int) {
    companion object {
        fun read(session: MediaExportSession): ExportPalette {
            UiColors.refresh(session.activity)
            return ExportPalette(UiColors.IsNight, UiColors.Panel, UiColors.Card, UiColors.Text,
                UiColors.Muted, UiColors.Brand, UiColors.Divider)
        }
    }
}

@Composable
internal fun MediaExportContent(session: MediaExportSession) {
    var palette by remember { mutableStateOf(ExportPalette.read(session)) }
    LaunchedEffect(session.active) {
        if (session.active) while (true) {
            palette = ExportPalette.read(session)
            delay(500)
        }
    }
    val colors = remember(palette) {
        val brand = Color(palette.brand)
        val surface = Color(palette.panel)
        val text = Color(palette.text)
        (if (palette.night) darkColorScheme() else lightColorScheme()).copy(
            primary = brand,
            onPrimary = if (1.05f / (brand.luminance() + .05f) >= 3f) Color.White else Color.Black,
            primaryContainer = lerp(surface, brand, if (palette.night) .24f else .10f),
            onPrimaryContainer = text, surface = surface, onSurface = text,
            surfaceContainer = Color(palette.card), onSurfaceVariant = Color(palette.muted),
            outlineVariant = Color(palette.divider), surfaceTint = brand,
        )
    }
    MaterialTheme(colorScheme = colors) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            Surface(Modifier.fillMaxWidth().padding(8.dp)
                .heightIn(max = (maxHeight * .82f - 16.dp).coerceAtLeast(120.dp)),
                shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, colors.outlineVariant),
                shadowElevation = 6.dp) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(session.title, Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        TextButton(session::dismiss, colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurfaceVariant)) {
                            Text("关闭")
                        }
                    }
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (session.audioUrl != null) {
                            Text("文件名", fontSize = 12.sp, color = colors.onSurfaceVariant)
                            OutlinedTextField(value = session.filename, onValueChange = { session.filename = it },
                                enabled = !session.started, singleLine = true,
                                textStyle = LocalTextStyle.current.copy(fontSize = 15.sp),
                                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = colors.primary.copy(alpha = .6f), unfocusedBorderColor = colors.outlineVariant.copy(alpha = .5f),
                                    focusedContainerColor = colors.surfaceContainerLowest, unfocusedContainerColor = colors.surfaceContainerLowest))
                            Text("无需先播放，下载后按原始格式保存，自动补齐文件扩展名。",
                                fontSize = 13.sp, color = colors.onSurfaceVariant)
                        } else {
                            Text("${session.items.size} 张表情", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            Text("保存到 Pictures/QDHelper，保留原始格式。关闭窗口后停止后续导出。",
                                fontSize = 13.sp, color = colors.onSurfaceVariant)
                        }
                        Text(session.status, fontSize = 14.sp)
                        if (session.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.primary)
                        if (session.failures.isNotEmpty()) {
                            Text("未保存的文件", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            session.failures.forEach { Text(it, fontSize = 13.sp, color = colors.onSurfaceVariant) }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        FilledTonalButton(session::start,
                            enabled = session.ready && !session.busy && !session.started,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = colors.primaryContainer, contentColor = colors.primary)) {
                            Text(if (session.busy) "正在导出" else "导出")
                        }
                    }
                }
            }
        }
    }
}
