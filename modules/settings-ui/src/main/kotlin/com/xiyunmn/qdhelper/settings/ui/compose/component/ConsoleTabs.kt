package com.xiyunmn.qdhelper.settings.ui.compose.component

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset

@Composable
internal fun ConsoleTabs(
    tabs: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tabs.isEmpty()) return
    val selectedIndex = tabs.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        BoxWithConstraints(Modifier.padding(4.dp)) {
            val gap = 4.dp
            val tabWidth = (maxWidth - gap * (tabs.size - 1)) / tabs.size
            val indicatorOffset = animateDpAsState((tabWidth + gap) * selectedIndex,
                animationSpec = tween(180, easing = FastOutSlowInEasing), label = "console-tab-position")
            // One opaque indicator moves beneath the tabs; it never blends toward transparent black.
            Box(Modifier.matchParentSize()) {
                Box(Modifier.offset { IntOffset(indicatorOffset.value.roundToPx(), 0) }
                    .width(tabWidth).fillMaxHeight().clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer))
            }
            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                tabs.forEachIndexed { index, (id, title) ->
                    val active = selectedIndex == index
                    Tab(active, onClick = { if (selected != id) onSelect(id) },
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)),
                        selectedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        text = {
                            Text(title, maxLines = 1, style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Medium)
                        })
                }
            }
        }
    }
}
