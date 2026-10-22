package com.xiyunmn.qdhelper.settings.ui.compose.component

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** Presentation only: callers retain their range semantics, validation and persistence. */
@Composable
internal fun ConsoleNumberRange(
    first: String, last: String, firstLabel: String, lastLabel: String,
    onFirstChange: (String) -> Unit, onLastChange: (String) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, isError: Boolean = false,
    firstPlaceholder: String? = null, lastPlaceholder: String? = null,
) {
    val focus = LocalFocusManager.current
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ConsoleTextField(first, onFirstChange, Modifier.weight(1f), enabled = enabled, isError = isError,
            label = { Text(firstLabel) }, placeholder = firstPlaceholder?.let { { Text(it) } }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Next) }))
        Text("——", color = MaterialTheme.colorScheme.onSurfaceVariant)
        ConsoleTextField(last, onLastChange, Modifier.weight(1f), enabled = enabled, isError = isError,
            label = { Text(lastLabel) }, placeholder = lastPlaceholder?.let { { Text(it) } }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }))
    }
}
