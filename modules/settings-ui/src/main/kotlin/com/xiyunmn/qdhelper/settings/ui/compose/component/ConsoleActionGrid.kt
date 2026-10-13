package com.xiyunmn.qdhelper.settings.ui.compose.component

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** Equal-width actions fill each line; narrow layouts or large fonts use one column. */
@Composable
internal fun ConsoleActionGrid(count: Int, content: @Composable (Int, Modifier) -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 280.dp * fontScale) 2 else 1
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            (0 until count).chunked(columns).forEach { indices ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    indices.forEach { index -> content(index, Modifier.weight(1f).fillMaxHeight()) }
                }
            }
        }
    }
}
