package com.xiyunmn.qdhelper.feature.bookshield.ui

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiyunmn.qdhelper.shared.ui.UiColors
import kotlinx.coroutines.delay

private data class QuickShieldPalette(val night: Boolean, val panel: Int, val card: Int, val text: Int,
    val muted: Int, val brand: Int, val divider: Int) {
    companion object {
        fun read(session: QuickShieldSession): QuickShieldPalette {
            UiColors.refresh(session.activity)
            return QuickShieldPalette(UiColors.IsNight, UiColors.Panel, UiColors.Card, UiColors.Text,
                UiColors.Muted, UiColors.Brand, UiColors.Divider)
        }
    }
}

@Composable
internal fun QuickShieldContent(session: QuickShieldSession) {
    var palette by remember { mutableStateOf(QuickShieldPalette.read(session)) }
    LaunchedEffect(session.active) {
        if (session.active) while (true) {
            palette = QuickShieldPalette.read(session)
            session.refresh()
            delay(500)
        }
    }
    val colors = remember(palette) {
        val brand = Color(palette.brand)
        val surface = Color(palette.panel)
        val text = Color(palette.text)
        (if (palette.night) darkColorScheme() else lightColorScheme()).copy(
            primary = brand, onPrimary = if (1.05f / (brand.luminance() + .05f) >= 3f) Color.White else Color.Black,
            primaryContainer = lerp(surface, brand, .12f), onPrimaryContainer = text,
            surface = surface, onSurface = text, surfaceContainer = Color(palette.card),
            onSurfaceVariant = Color(palette.muted), outlineVariant = Color(palette.divider), surfaceTint = brand,
        )
    }
    MaterialTheme(colorScheme = colors) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            Surface(Modifier.fillMaxWidth().padding(8.dp).heightIn(max = (maxHeight - 16.dp).coerceAtLeast(120.dp)),
                shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, colors.outlineVariant), shadowElevation = 6.dp) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("快速屏蔽", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            Text("加入后自动启用屏蔽", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                        TextButton(session::dismiss, colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurfaceVariant)) { Text("关闭") }
                    }
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        session.rules.forEachIndexed { index, rule -> key(rule.kind) {
                            val present = session.membership[index]
                            Surface(shape = RoundedCornerShape(14.dp), color = colors.surfaceContainer) {
                                Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp).padding(top = 2.dp, bottom = 10.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(rule.kind.label, Modifier.weight(1f), fontSize = 13.sp, color = colors.onSurfaceVariant)
                                        OutlinedButton({ session.copy(index) }, shape = RoundedCornerShape(10.dp),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                            border = BorderStroke(1.dp, colors.outlineVariant),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onSurface)) { Text("复制") }
                                        val actionColor = if (present) Color(if (palette.night) 0xFFFFB060 else 0xFFAA4D00) else colors.primary
                                        FilledTonalButton({ session.change(index) }, enabled = !session.busy,
                                            shape = RoundedCornerShape(10.dp),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                            colors = ButtonDefaults.filledTonalButtonColors(containerColor = actionColor.copy(alpha = .12f), contentColor = actionColor)) {
                                            Text(if (present) "移出" else "加入")
                                        }
                                    }
                                    // Keep the full width for long titles; actions share the label row.
                                    Text(rule.value, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                                        fontFamily = if (rule.kind == QuickShieldRule.Kind.ID) FontFamily.Monospace else FontFamily.Default)
                                }
                            }
                        } }
                    }
                }
            }
        }
    }
}
