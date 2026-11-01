package com.xiyunmn.qdhelper.settings.ui.compose.editor

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.xiyunmn.qdhelper.settings.api.SettingsFontPreview
import com.xiyunmn.qdhelper.settings.api.SettingsFontPreviewController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun rememberFontLibraryRevision(source: SettingsFontPreviewController): Int {
    var revision by remember(source) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    DisposableEffect(source) {
        val listener = source.addChangedListener { scope.launch(Dispatchers.Main.immediate) { revision++ } }
        onDispose { listener.close() }
    }
    return revision
}

@Composable
internal fun rememberFontPreview(source: SettingsFontPreviewController?, key: String?, revision: Int): State<SettingsFontPreview?> {
    val context = LocalContext.current.applicationContext
    // Retain the last face while a replacement loads, including selection-triggered refreshes.
    return produceState<SettingsFontPreview?>(null, source, key, revision) {
        if (source == null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching { source.preview(context, key) }
                .getOrElse { SettingsFontPreview(message = "字体预览不可用") }
        }
    }
}
