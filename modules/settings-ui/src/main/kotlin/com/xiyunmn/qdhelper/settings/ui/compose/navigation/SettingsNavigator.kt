package com.xiyunmn.qdhelper.settings.ui.compose.navigation

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal class SettingsEntry(
    val id: Long, val destination: String, val editor: String? = null,
    focus: String? = null,
) {
    val scroll = LazyListState()
    val searchScroll = LazyListState()
    val tabEntries = mutableMapOf<String, SettingsEntry>()
    var selectedTab by mutableStateOf<String?>(null)
    val expanded = mutableStateMapOf<String, Boolean>()
    var focusRow by mutableStateOf(focus)
    var focusChoice by mutableStateOf<String?>(null)
}

/** Bound to AnimatedContent's entry, never the navigator's changing top of stack. */
internal val LocalSettingsEntry = staticCompositionLocalOf<SettingsEntry> { error("Missing settings entry") }

internal class SettingsNavigator {
    private var sequence = 0L
    val stack = mutableStateListOf(SettingsEntry(sequence++, "home"))
    var forward by mutableStateOf(true)
        private set
    val current get() = stack.last()
    fun open(destination: String, editor: String? = null, focus: String? = null) {
        if (current.destination == destination && current.editor == editor) {
            current.focusRow = focus
            return
        }
        forward = true
        stack.add(SettingsEntry(sequence++, destination, editor, focus))
    }
    fun back(): Boolean {
        if (stack.size == 1) return false
        forward = false
        stack.removeAt(stack.lastIndex)
        return true
    }
}
