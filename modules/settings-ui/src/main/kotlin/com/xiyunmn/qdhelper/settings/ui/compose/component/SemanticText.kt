package com.xiyunmn.qdhelper.settings.ui.compose.component

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

internal enum class SemanticTone { Info, Success, Warning, Error, Accent }

@Composable
internal fun semanticColor(tone: SemanticTone): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < .4f
    return when (tone) {
        SemanticTone.Info -> Color(0xFF0065FF)
        SemanticTone.Success -> if (dark) Color(0xFF20D977) else Color(0xFF009E50)
        SemanticTone.Warning -> if (dark) Color(0xFFF1CC72) else Color(0xFF896300)
        SemanticTone.Error -> if (dark) Color(0xFFFF4F70) else Color(0xFFEC2450)
        SemanticTone.Accent -> if (dark) Color(0xFFB078FF) else Color(0xFF8538EF)
    }
}

private val labelPattern = Regex("(?m)^(?=[^\\n:：]*[\\p{L}_])[^\\n:：]{1,22}[：:]")
private val timePattern = Regex("(?<![\\d:])(?:[01]?\\d|2[0-3]):[0-5]\\d(?::[0-5]\\d(?:\\.\\d{1,3})?)?(?![\\d:])")
private val metricPattern = Regex("\\d+(?:\\.\\d+)?\\s*(?:条|本|个|次|分钟|小时|秒|ms|KB|MB|%|倍|×)(?![A-Za-z])")
private val successPattern = Regex("(?m)^(?:已解析|已执行预热|成功|已保存|已启用)(?=\\s|[：:·]|$)")
private val warningPattern = Regex("(?m)^(?:未解析|尚未预热|未启用|未保存|草稿未保存|已取消|待核验)(?=\\s|[：:·]|$)")
private val errorPattern = Regex("(?m)^(?:失败|解析失败|读取失败|保存失败|错误|不可用)(?=\\s|[：:·]|$)")

/** Annotates existing text without changing its selectable/copyable content. */
@Composable
internal fun SemanticText(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodySmall, emphasizedText: String? = null) {
    val info = semanticColor(SemanticTone.Info)
    val success = semanticColor(SemanticTone.Success)
    val warning = semanticColor(SemanticTone.Warning)
    val error = semanticColor(SemanticTone.Error)
    val accent = semanticColor(SemanticTone.Accent)
    val annotated = remember(text, info, success, warning, error, accent, emphasizedText) {
        buildAnnotatedString {
            append(text)
            // Large identity/diagnostic dumps remain plain text beyond this prefix.
            val prefix = text.take(32_768)
            fun mark(pattern: Regex, color: Color) {
                pattern.findAll(prefix).forEach { match ->
                    addStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold), match.range.first, match.range.last + 1)
                }
            }
            mark(labelPattern, info)
            mark(metricPattern, info)
            mark(timePattern, info)
            mark(successPattern, success)
            mark(warningPattern, warning)
            mark(errorPattern, error)
            if (!emphasizedText.isNullOrEmpty()) {
                val start = prefix.indexOf(emphasizedText)
                if (start >= 0) addStyle(
                    SpanStyle(color = accent, fontWeight = FontWeight.SemiBold),
                    start, start + emphasizedText.length,
                )
            }
        }
    }
    Text(annotated, modifier, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun SemanticBadge(label: String, tone: SemanticTone) {
    val color = semanticColor(tone)
    Surface(color = color.copy(alpha = .12f), shape = RoundedCornerShape(6.dp)) {
        Text(label, Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun ConsoleActionButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, tone: SemanticTone? = null,
    icon: String? = null, trailingIcon: String? = null, busy: Boolean = false) {
    val accent = tone?.let { semanticColor(it) } ?: MaterialTheme.colorScheme.primary
    FilledTonalButton(onClick, modifier.heightIn(min = 44.dp), enabled = enabled, shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = accent.copy(alpha = .12f), contentColor = accent)) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(16.dp), color = LocalContentColor.current, strokeWidth = 2.dp)
            Spacer(Modifier.width(6.dp))
        } else if (icon != null) {
            ConsoleIcon(icon, Modifier.size(18.dp), LocalContentColor.current)
            Spacer(Modifier.width(6.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
        if (trailingIcon != null) {
            Spacer(Modifier.width(6.dp))
            ConsoleIcon(trailingIcon, Modifier.size(16.dp), LocalContentColor.current)
        }
    }
}
