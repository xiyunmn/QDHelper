package com.xiyunmn.qdhelper.settings.ui.compose.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun SettingsChildSurface(checked: Boolean = false, bottomPadding: Dp = 12.dp, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = bottomPadding),
        shape = RoundedCornerShape(14.dp),
        color = colors.primary.copy(alpha = .07f),
        border = BorderStroke(.5.dp, if (checked) colors.primary.copy(alpha = .18f) else colors.outlineVariant.copy(alpha = .4f)),
        content = content,
    )
}

/** One child-switch presentation, with optional controls beneath the switch row. */
@Composable
internal fun ChildToggleSetting(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true, summary: String? = null, badge: String? = null, badgeColor: Color? = null,
    footer: @Composable (() -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    SettingsChildSurface(checked) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
                    .heightIn(min = 64.dp).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = colors.onSurface)
                    if (!summary.isNullOrBlank()) Text(summary, fontSize = 12.sp, lineHeight = 17.sp, color = colors.onSurfaceVariant)
                    if (!badge.isNullOrBlank()) Text(badge, fontSize = 10.sp, color = badgeColor ?: colors.onSurfaceVariant)
                }
                Switch(checked, onCheckedChange = null, enabled = enabled)
            }
            footer?.invoke()
        }
    }
}
