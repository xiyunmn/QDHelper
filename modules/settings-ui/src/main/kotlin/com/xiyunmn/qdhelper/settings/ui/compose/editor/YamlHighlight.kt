package com.xiyunmn.qdhelper.settings.ui.compose.editor

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.xiyunmn.qdhelper.settings.ui.YamlSyntaxKind
import com.xiyunmn.qdhelper.settings.ui.YamlSyntaxToken
import com.xiyunmn.qdhelper.settings.ui.yamlSyntaxTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Debounced off-main lexing. Large documents remain editable without full-text span allocation. */
@Composable
internal fun rememberYamlHighlight(text: String): VisualTransformation {
    var parsed by remember { mutableStateOf<Pair<String, List<YamlSyntaxToken>>?>(null) }
    val colors = MaterialTheme.colorScheme
    LaunchedEffect(text) {
        parsed = null
        if (text.length > 128 * 1024) return@LaunchedEffect
        delay(250)
        parsed = text to withContext(Dispatchers.Default) { yamlSyntaxTokens(text) }
    }
    return remember(parsed, colors) {
        VisualTransformation { input ->
            val tokens = parsed?.takeIf { it.first == input.text }?.second
            if (tokens == null) TransformedText(input, OffsetMapping.Identity)
            else TransformedText(buildAnnotatedString {
                append(input)
                tokens.forEach { token ->
                    val color = when (token.kind) {
                        YamlSyntaxKind.COMMENT -> colors.onSurfaceVariant
                        YamlSyntaxKind.KEY, YamlSyntaxKind.KEYWORD -> colors.primary
                        YamlSyntaxKind.NUMBER, YamlSyntaxKind.STRING -> colors.onSurface
                        YamlSyntaxKind.PUNCTUATION -> colors.outline
                    }
                    addStyle(SpanStyle(color = color, fontWeight = if (token.kind == YamlSyntaxKind.KEY) androidx.compose.ui.text.font.FontWeight.SemiBold else null), token.start, token.end)
                }
            }, OffsetMapping.Identity)
        }
    }
}
