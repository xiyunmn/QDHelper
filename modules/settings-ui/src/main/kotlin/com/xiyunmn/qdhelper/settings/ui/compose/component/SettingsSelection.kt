package com.xiyunmn.qdhelper.settings.ui.compose.component

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.foundation.text.selection.rememberSelectionState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/** Mounted containers own their selection; the panel only coordinates dismissal. */
internal class SettingsSelections {
    private val states = mutableSetOf<SelectionState>()
    fun register(state: SelectionState) { states.add(state) }
    fun unregister(state: SelectionState) { state.clear(); states.remove(state) }
    fun selected(): List<SelectionState> = states.filter { it.selectedTexts.isNotEmpty() }
    fun dismiss(): Boolean {
        val selected = selected()
        selected.forEach { it.clear() }
        return selected.isNotEmpty()
    }
}

internal val LocalSettingsSelections = staticCompositionLocalOf<SettingsSelections> { error("Missing selection coordinator") }

@Composable
internal fun SettingsSelectionContainer(content: @Composable () -> Unit) {
    val coordinator = LocalSettingsSelections.current
    val state = rememberSelectionState()
    DisposableEffect(coordinator, state) {
        coordinator.register(state)
        onDispose { coordinator.unregister(state) }
    }
    SelectionContainer(state = state, content = content)
}

/** Observe short taps without consuming them, so buttons and selection gestures keep working. */
internal fun Modifier.dismissSelectionOnTap(coordinator: SettingsSelections): Modifier = pointerInput(coordinator) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
        // Capture only an existing selection; never clear one created by this long press.
        val selected = coordinator.selected()
        if (selected.isEmpty()) return@awaitEachGesture
        var moved = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Final)
            if (event.changes.size != 1) moved = true
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
            if (!change.pressed) {
                if (!moved && change.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) {
                    selected.forEach { it.clear() }
                }
                break
            }
        }
    }
}
