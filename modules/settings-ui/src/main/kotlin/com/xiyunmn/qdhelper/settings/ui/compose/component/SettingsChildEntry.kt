package com.xiyunmn.qdhelper.settings.ui.compose.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun SettingsChildEntry(title: String, summary: String?, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = 6.dp),
        shape = RoundedCornerShape(14.dp), color = colors.primary.copy(alpha = .07f)) {
        BoxWithConstraints {
            val inlineSummary = !summary.isNullOrBlank() && summary.length <= 9 &&
                maxWidth >= 280.dp && LocalDensity.current.fontScale <= 1.2f
            Row(Modifier.fillMaxWidth().alpha(if (enabled) 1f else .46f)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = colors.onSurface)
                    if (!inlineSummary && !summary.isNullOrBlank()) Text(summary, fontSize = 12.sp,
                        lineHeight = 17.sp, color = colors.onSurfaceVariant)
                }
                if (inlineSummary) Text(summary.orEmpty(), fontSize = 12.sp, color = colors.onSurfaceVariant)
                Surface(Modifier.size(28.dp), shape = CircleShape, color = colors.primary.copy(alpha = .10f)) {
                    Box(contentAlignment = Alignment.Center) {
                        ConsoleIcon("next", Modifier.size(18.dp), color = colors.primary)
                    }
                }
            }
        }
    }
}
