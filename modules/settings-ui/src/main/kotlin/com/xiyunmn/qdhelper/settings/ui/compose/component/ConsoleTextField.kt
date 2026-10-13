package com.xiyunmn.qdhelper.settings.ui.compose.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Shared field styling for search and short forms. */
@Composable
internal fun ConsoleTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    isError: Boolean = false,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val requester = remember { BringIntoViewRequester() }
    val imeBottom = if (focused) WindowInsets.ime.getBottom(LocalDensity.current) else 0
    LaunchedEffect(focused, imeBottom) {
        if (focused && imeBottom > 0) {
            // Let IME insets settle before revealing a short field in its scrolling page.
            delay(80)
            withFrameNanos { }
            requester.bringIntoView()
        }
    }
    val base = lerp(colors.surfaceContainerLow, colors.onSurface, .025f)
    val container by animateColorAsState(if (focused) lerp(base, colors.primary, .025f) else base, label = "input-glow")
    val border = colors.outlineVariant.copy(alpha = .5f)
    OutlinedTextField(
        value, onValueChange, modifier.bringIntoViewRequester(requester), enabled = enabled,
        singleLine = singleLine, minLines = minLines, maxLines = maxLines,
        isError = isError, label = label, placeholder = placeholder,
        supportingText = supportingText, leadingIcon = leadingIcon, trailingIcon = trailingIcon,
        keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
        visualTransformation = visualTransformation, textStyle = textStyle,
        shape = shape, interactionSource = interactions,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = container,
            unfocusedContainerColor = container,
            disabledContainerColor = colors.surfaceContainerHigh.copy(alpha = .25f),
            errorContainerColor = colors.errorContainer.copy(alpha = .18f),
            focusedBorderColor = border,
            unfocusedBorderColor = border,
            disabledBorderColor = colors.outlineVariant.copy(alpha = .2f),
            errorBorderColor = semanticColor(SemanticTone.Error),
            focusedLabelColor = colors.onSurfaceVariant,
            cursorColor = colors.primary,
        ),
    )
}
