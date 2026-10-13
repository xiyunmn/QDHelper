package com.xiyunmn.qdhelper.capability.freecopy

import android.app.Activity
import android.graphics.Typeface
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.foundation.text.selection.rememberSelectionState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.xiyunmn.qdhelper.shared.ui.UiColors
import kotlinx.coroutines.delay

internal fun copyAnnotatedText(content: CharSequence): AnnotatedString {
    val result = AnnotatedString.Builder(content.toString())
    if (content is Spanned) {
        content.getSpans(0, content.length, Any::class.java).forEach { span ->
            val start = content.getSpanStart(span).coerceIn(0, content.length)
            val end = content.getSpanEnd(span).coerceIn(start, content.length)
            val style = when (span) {
                is StyleSpan -> SpanStyle(
                    fontWeight = if (span.style and Typeface.BOLD != 0) FontWeight.Bold else null,
                    fontStyle = if (span.style and Typeface.ITALIC != 0) FontStyle.Italic else null,
                )
                is RelativeSizeSpan -> SpanStyle(fontSize = span.sizeChange.em)
                is UnderlineSpan -> SpanStyle(textDecoration = TextDecoration.Underline)
                else -> null
            }
            if (style != null && end > start) result.addStyle(style, start, end)
        }
    }
    return result.toAnnotatedString()
}

internal data class CopyPalette(val night: Boolean, val panel: Int, val card: Int, val text: Int, val muted: Int, val brand: Int, val divider: Int) {
    companion object {
        fun read(activity: Activity): CopyPalette {
            UiColors.refresh(activity)
            return CopyPalette(UiColors.IsNight, UiColors.Panel, UiColors.Card, UiColors.Text, UiColors.Muted, UiColors.Brand, UiColors.Divider)
        }
    }
}

internal fun copyDialogColors(palette: CopyPalette): ColorScheme {
    val brand = Color(palette.brand)
    val surface = Color(palette.panel)
    val card = Color(palette.card)
    val text = Color(palette.text)
    val muted = Color(palette.muted)
    val container = lerp(surface, brand, if (palette.night) .20f else .10f)
    // Never inherit M3's purple onPrimary after replacing its primary with the host red.
    val onBrand = if (1.05f / (brand.luminance() + .05f) >= 3f) Color.White else Color.Black
    return (if (palette.night) darkColorScheme() else lightColorScheme()).copy(
        primary = brand, onPrimary = onBrand, primaryContainer = container, onPrimaryContainer = text,
        secondary = brand, onSecondary = onBrand, secondaryContainer = container, onSecondaryContainer = text,
        tertiary = brand, onTertiary = onBrand, tertiaryContainer = container, onTertiaryContainer = text,
        background = surface, onBackground = text, surface = surface, onSurface = text,
        surfaceVariant = card, onSurfaceVariant = muted, surfaceTint = brand,
        outline = muted, outlineVariant = Color(palette.divider),
        surfaceContainerLowest = surface, surfaceContainerLow = card, surfaceContainer = card,
        surfaceContainerHigh = card, surfaceContainerHighest = card,
        surfaceBright = card, surfaceDim = surface,
        inverseSurface = text, inverseOnSurface = surface, inversePrimary = brand,
        primaryFixed = container, primaryFixedDim = container, onPrimaryFixed = text, onPrimaryFixedVariant = text,
        secondaryFixed = container, secondaryFixedDim = container, onSecondaryFixed = text, onSecondaryFixedVariant = text,
        tertiaryFixed = container, tertiaryFixedDim = container, onTertiaryFixed = text, onTertiaryFixedVariant = text,
    )
}

@Composable
internal fun CopyDialogContent(
    activity: Activity,
    content: CharSequence,
    title: String,
    active: Boolean,
    dismiss: () -> Unit,
    copyAll: () -> Unit,
    installSelectionClear: (() -> Unit) -> Unit,
) {
    var palette by remember { mutableStateOf(CopyPalette.read(activity)) }
    LaunchedEffect(active) {
        if (active) while (true) {
            palette = CopyPalette.read(activity)
            delay(500)
        }
    }
    val colors = remember(palette) { copyDialogColors(palette) }
    val selection = rememberSelectionState()
    DisposableEffect(selection) {
        installSelectionClear { selection.clear() }
        onDispose { selection.clear() }
    }
    val text = remember(content) { copyAnnotatedText(content) }
    MaterialTheme(colorScheme = colors) {
        // Window constraints, rather than physical display size, also cover split-screen/rotation.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(8.dp).heightIn(max = (maxHeight - 16.dp).coerceAtLeast(120.dp))
                    .clearCopySelectionOnTap(selection),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, colors.outlineVariant),
                shadowElevation = 6.dp,
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(14.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                        color = colors.surfaceContainer, shape = RoundedCornerShape(14.dp),
                    ) {
                        SelectionContainer(state = selection) {
                            Text(
                                text, fontSize = 15.sp, lineHeight = 24.sp,
                                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(14.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = dismiss, modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onSurfaceVariant),
                            border = BorderStroke(1.dp, colors.outlineVariant),
                        ) { Text("取消") }
                        Button(onClick = copyAll, modifier = Modifier.weight(1f)) { Text("复制全文") }
                    }
                }
            }
        }
    }
}

/** Observe an existing selection only; never consume taps, scrolling or handle dragging. */
private fun Modifier.clearCopySelectionOnTap(selection: SelectionState): Modifier = pointerInput(selection) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
        if (selection.selectedTexts.isEmpty()) return@awaitEachGesture
        var moved = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Final)
            if (event.changes.size != 1) moved = true
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
            if (!change.pressed) {
                if (!moved && change.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) selection.clear()
                break
            }
        }
    }
}
