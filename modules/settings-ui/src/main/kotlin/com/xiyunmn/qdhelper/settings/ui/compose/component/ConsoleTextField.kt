package com.xiyunmn.qdhelper.settings.ui.compose.component

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Shared field styling for search and short forms. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    fillHeight: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val requester = remember { BringIntoViewRequester() }
    val imeBottom = if (focused) WindowInsets.ime.getBottom(LocalDensity.current) else 0
    val imeTargetBottom = if (focused) WindowInsets.imeAnimationTarget.getBottom(LocalDensity.current) else 0
    LaunchedEffect(focused, imeBottom, imeTargetBottom) {
        if (focused && imeBottom > 0 && imeTargetBottom > 0) {
            // Let IME insets settle before revealing a short field in its scrolling page.
            delay(80)
            withFrameNanos { }
            requester.bringIntoView()
        }
    }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = colors.surfaceContainerLowest,
        unfocusedContainerColor = colors.surfaceContainerLowest,
        disabledContainerColor = colors.surfaceContainerHigh.copy(alpha = .25f),
        errorContainerColor = colors.errorContainer.copy(alpha = .18f),
        focusedBorderColor = consoleInputBorder(focused = true),
        unfocusedBorderColor = consoleInputBorder(focused = false),
        disabledBorderColor = consoleInputBorder(focused = false, enabled = false),
        errorBorderColor = consoleInputBorder(focused = false, isError = true),
        focusedTextColor = colors.onSurface,
        unfocusedTextColor = colors.onSurface,
        cursorColor = colors.primary,
    )
    Column(modifier.bringIntoViewRequester(requester).semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        label?.let { content ->
            val labelColor = if (enabled) colors.onSurfaceVariant else colors.onSurface.copy(alpha = .38f)
            CompositionLocalProvider(LocalContentColor provides labelColor) {
                ProvideTextStyle(MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp)) { content() }
            }
        }
        // Share the frame without forcing rule lists or full-page editors into a short field.
        val fieldModifier = Modifier.fillMaxWidth().then(
            if (fillHeight && !singleLine) Modifier.weight(1f)
            else Modifier.heightIn(min = if (singleLine) 40.dp else 96.dp)
        ).semantics { if (isError) error("输入有误") }
        BasicTextField(value, onValueChange, fieldModifier,
            enabled = enabled, singleLine = singleLine,
            minLines = if (singleLine) 1 else minLines, maxLines = if (singleLine) 1 else maxLines,
            textStyle = textStyle.copy(color = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = .38f)),
            keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
            visualTransformation = visualTransformation, interactionSource = interactions,
            cursorBrush = SolidColor(colors.primary),
            decorationBox = { innerTextField ->
                OutlinedTextFieldDefaults.DecorationBox(value, innerTextField, enabled, singleLine,
                    visualTransformation, interactions, isError = isError,
                    placeholder = placeholder?.let { content -> { ProvideTextStyle(textStyle) { content() } } },
                    leadingIcon = leadingIcon, trailingIcon = trailingIcon, colors = fieldColors,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = if (singleLine) 9.dp else 12.dp),
                    container = {
                        OutlinedTextFieldDefaults.Container(enabled, isError, interactions, colors = fieldColors, shape = shape,
                            focusedBorderThickness = 1.dp, unfocusedBorderThickness = 1.dp)
                    })
            })
        supportingText?.let { content ->
            CompositionLocalProvider(LocalContentColor provides if (isError) colors.error else colors.onSurfaceVariant) {
                ProvideTextStyle(MaterialTheme.typography.bodySmall) { content() }
            }
        }
    }
}

@Composable
internal fun consoleInputBorder(focused: Boolean, enabled: Boolean = true, isError: Boolean = false): Color {
    val colors = MaterialTheme.colorScheme
    return when {
        !enabled -> colors.outlineVariant.copy(alpha = .2f)
        isError -> semanticColor(SemanticTone.Error)
        focused -> colors.primary.copy(alpha = .6f)
        else -> colors.outlineVariant.copy(alpha = .5f)
    }
}
