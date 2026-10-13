package com.xiyunmn.qdhelper.settings.ui.compose.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xiyunmn.qdhelper.shared.compose.ModuleFloatingDialog

/** Cancellation is neutral; only the action carries its semantic emphasis. */
@Composable
internal fun ConsoleConfirmationDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    tone: SemanticTone? = null,
) {
    ModuleFloatingDialog(onDismissRequest = onDismiss) {
        BoxWithConstraints(Modifier.padding(8.dp)) {
            val colors = MaterialTheme.colorScheme
            Surface(Modifier.fillMaxWidth().heightIn(max = maxHeight), shape = RoundedCornerShape(24.dp),
                color = colors.surfaceContainerLow, border = BorderStroke(.5.dp, colors.outlineVariant), shadowElevation = 6.dp) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    Text(text, Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), color = colors.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextButton(onDismiss, Modifier.weight(1f), colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurfaceVariant)) { Text("取消") }
                        ConsoleActionButton(confirmLabel, onConfirm, Modifier.weight(1f), tone = tone)
                    }
                }
            }
        }
    }
}

/** Current tool commands: destructive clearing versus sensitive clipboard export. */
internal fun toolConfirmationTone(commandId: String): SemanticTone? = when (commandId) {
    "clear" -> SemanticTone.Error
    "copy" -> SemanticTone.Warning
    else -> null
}
