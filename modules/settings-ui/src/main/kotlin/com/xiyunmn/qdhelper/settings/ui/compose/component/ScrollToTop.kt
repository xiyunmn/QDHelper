package com.xiyunmn.qdhelper.settings.ui.compose.component

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Also handles a diagnostic dump occupying one very tall lazy item. */
@Composable
internal fun ScrollToTopContainer(
    state: LazyListState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val threshold = with(LocalDensity.current) { 240.dp.roundToPx() }
    val visible by remember(state, threshold) {
        derivedStateOf { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > threshold }
    }
    val scope = rememberCoroutineScope()
    Box(modifier.fillMaxSize()) {
        content()
        if (visible) {
            SmallFloatingActionButton(
                onClick = { scope.launch { state.animateScrollToItem(0) } },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).size(56.dp).semantics { contentDescription = "返回顶部" },
                shape = RoundedCornerShape(16.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) { ConsoleIcon("top", Modifier.size(28.dp), color = MaterialTheme.colorScheme.onPrimaryContainer) }
        }
    }
}
