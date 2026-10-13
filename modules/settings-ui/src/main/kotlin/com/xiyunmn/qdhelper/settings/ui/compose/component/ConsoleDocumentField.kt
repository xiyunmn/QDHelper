package com.xiyunmn.qdhelper.settings.ui.compose.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.ceil

/** A stable document frame with explicit caret-aware scrolling inside the IME-safe viewport. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ConsoleDocumentField(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    syntax: VisualTransformation = VisualTransformation.None,
) {
    val colors = MaterialTheme.colorScheme
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val scroll = rememberScrollState()
    var viewportHeight by remember { mutableIntStateOf(0) }
    var textLayout by remember { mutableStateOf<(() -> TextLayoutResult?)?>(null) }
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    val margin = with(density) { 24.dp.roundToPx() }
    val text = state.text.toString()
    val selection = state.selection
    val styled = remember(text, syntax) { syntax.filter(AnnotatedString(text)).text }
    val output = remember(styled) {
        OutputTransformation {
            // YAML highlighting only adds spans: text and selection offsets never change.
            if (asCharSequence().toString() == styled.text) {
                styled.spanStyles.forEach { addStyle(it.item, it.start, it.end) }
            }
        }
    }
    LaunchedEffect(focused, imeBottom, viewportHeight, selection, text) {
        if (focused && imeBottom > 0 && viewportHeight > 0) {
            // Wait for the final IME/layout geometry, then reveal the active selection edge.
            // Scroll position is deliberately not an effect key: manual scrolling stays free.
            delay(100)
            withFrameNanos { }
            val layout = textLayout?.invoke() ?: return@LaunchedEffect
            val caret = layout.getCursorRect(state.selection.end.coerceIn(0, layout.layoutInput.text.length))
            val target = documentCaretScrollTarget(caret.top, caret.bottom, viewportHeight, scroll.value, scroll.maxValue, margin)
            if (target != scroll.value) scroll.animateScrollTo(target, tween(180))
        }
    }
    val shape = RoundedCornerShape(14.dp)
    val base = lerp(colors.surfaceContainerLow, colors.onSurface, .025f)
    val container by animateColorAsState(if (focused) lerp(base, colors.primary, .025f) else base, label = "document-glow")
    Column(modifier.clip(shape).background(container).border(1.dp, colors.outlineVariant.copy(alpha = .5f), shape)) {
        // Account for the frame's actual bottom position before applying keyboard insets.
        // Otherwise its page margin plus inner padding form an opaque strip above the IME.
        Column(Modifier.fillMaxSize().recalculateWindowInsets().imePadding()
            .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = if (imeBottom > 0) 0.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("YAML", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            BasicTextField(
                state = state,
                modifier = Modifier.fillMaxWidth().weight(1f).onSizeChanged { viewportHeight = it.height },
                enabled = enabled,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, color = colors.onSurface),
                interactionSource = interactions,
                cursorBrush = SolidColor(colors.primary),
                outputTransformation = output,
                scrollState = scroll,
                onTextLayout = { getResult -> textLayout = getResult },
            )
        }
    }
}

/** Smallest scroll adjustment that leaves breathing room around the caret. */
internal fun documentCaretScrollTarget(top: Float, bottom: Float, viewport: Int, current: Int, maximum: Int, requestedMargin: Int): Int {
    if (viewport <= 0) return current
    val margin = requestedMargin.coerceIn(0, viewport / 4)
    val target = when {
        top < current + margin -> top - margin
        bottom > current + viewport - margin -> bottom - viewport + margin
        else -> current.toFloat()
    }
    return ceil(target.toDouble()).toInt().coerceIn(0, maximum.coerceAtLeast(0))
}
