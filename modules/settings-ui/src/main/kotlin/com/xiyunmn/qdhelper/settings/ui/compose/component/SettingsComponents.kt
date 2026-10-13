package com.xiyunmn.qdhelper.settings.ui.compose.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.res.painterResource
import com.composables.icons.lucide.R as Lucide
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun SettingLine(
    title: String, summary: String? = null, enabled: Boolean = true,
    badge: String? = null, onClick: (() -> Unit)? = null,
    badgeColor: Color? = null,
    semanticSummary: Boolean = false,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().alpha(if (enabled) 1f else .46f)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(title, Modifier.weight(1f, fill = false), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                if (!badge.isNullOrEmpty()) Surface(color = badgeColor?.copy(alpha = .12f) ?: MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(5.dp)) {
                    Text(badge, Modifier.padding(horizontal = 5.dp, vertical = 2.dp), fontSize = 10.sp, color = badgeColor ?: MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!summary.isNullOrBlank()) {
                if (semanticSummary) SemanticText(summary)
                else Text(summary, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
        trailing?.invoke()
    }
}

@Composable
internal fun SectionLabel(text: String) {
    Text(text, Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, fontWeight = FontWeight.Medium)
}


@Composable
internal fun Modifier.settingsFocusHighlight(focused: Boolean): Modifier {
    val colors = MaterialTheme.colorScheme
    val tint = colors.primary
    val opacity = animateFloatAsState(
        targetValue = if (focused) (if (colors.surface.luminance() < .5f) .07f else .035f) else 0f,
        animationSpec = tween(if (focused) 140 else 420, easing = LinearOutSlowInEasing),
        label = "setting-focus-opacity",
    )
    // Keep the underlying surface and tint unchanged. Interpolating an opaque surface
    // to transparent black produces a dark intermediate frame, perceived as a flash.
    // Read animation state during drawing so rows are not recomposed every frame.
    return drawBehind { drawRect(tint.copy(alpha = opacity.value)) }
}

@Composable
internal fun ConsoleSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .3f)), content = content)
}

@Composable
internal fun categoryAccent(id: String): Color {
    return when (id) {
        "reading", "fonts", "account", "backup", "shield-quick", "book-shield.stats" -> semanticColor(SemanticTone.Info)
        "interface", "glass", "page-customization", "mine", "selection", "detail-layout", "cookie", "shield-yaml" -> semanticColor(SemanticTone.Accent)
        "content", "shield", "privacy", "tools", "resolver" -> semanticColor(SemanticTone.Success)
        else -> MaterialTheme.colorScheme.primary
    }
}

@Composable
internal fun IconTile(id: String, modifier: Modifier = Modifier) {
    val accent = categoryAccent(id)
    Surface(modifier.size(38.dp), color = accent.copy(alpha = .12f), shape = RoundedCornerShape(11.dp)) {
        Box(contentAlignment = Alignment.Center) { ConsoleIcon(id, color = accent) }
    }
}

@Composable
internal fun Chevron() { ConsoleIcon("next", Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant) }

/** Packaged Lucide vectors. One icon per category/action; no fabricated host artwork. */
@Composable
internal fun ConsoleIcon(id: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val drawable = when (id) {
        "back" -> Lucide.drawable.lucide_ic_arrow_left
        "next" -> Lucide.drawable.lucide_ic_chevron_right
        "down" -> Lucide.drawable.lucide_ic_chevron_down
        "up" -> Lucide.drawable.lucide_ic_chevron_up
        "top" -> Lucide.drawable.lucide_ic_arrow_up_to_line
        "drag" -> Lucide.drawable.lucide_ic_grip_vertical
        "copy" -> Lucide.drawable.lucide_ic_copy
        "reveal" -> Lucide.drawable.lucide_ic_eye
        "mask" -> Lucide.drawable.lucide_ic_eye_off
        "search" -> Lucide.drawable.lucide_ic_search
        "close" -> Lucide.drawable.lucide_ic_x
        "more" -> Lucide.drawable.lucide_ic_ellipsis_vertical
        "restart" -> Lucide.drawable.lucide_ic_rotate_cw
        "reading" -> Lucide.drawable.lucide_ic_book_open
        "fonts" -> Lucide.drawable.lucide_ic_type
        "interface", "page-customization", "mine", "selection", "detail-layout" -> Lucide.drawable.lucide_ic_panels_top_left
        "about" -> Lucide.drawable.lucide_ic_info
        "about.repository" -> Lucide.drawable.lucide_ic_github
        "about.author" -> Lucide.drawable.lucide_ic_user_round
        "account" -> Lucide.drawable.lucide_ic_workflow
        "privacy", "shield" -> Lucide.drawable.lucide_ic_shield
        "content" -> Lucide.drawable.lucide_ic_list_filter
        "book-shield.stats" -> Lucide.drawable.lucide_ic_chart_column
        "power" -> Lucide.drawable.lucide_ic_power
        "resolver" -> Lucide.drawable.lucide_ic_scan_line
        "backup" -> Lucide.drawable.lucide_ic_file_output
        "cookie" -> Lucide.drawable.lucide_ic_key_round
        "glass" -> Lucide.drawable.lucide_ic_rectangle_horizontal
        else -> Lucide.drawable.lucide_ic_settings_2
    }
    Icon(painterResource(drawable), contentDescription = null, modifier = modifier.size(24.dp), tint = color)
}

@Composable
internal fun ConsoleIconButton(id: String, label: String, onClick: () -> Unit) {
    IconButton(onClick, Modifier.semantics { contentDescription = label }) { ConsoleIcon(id, color = MaterialTheme.colorScheme.onSurface) }
}
