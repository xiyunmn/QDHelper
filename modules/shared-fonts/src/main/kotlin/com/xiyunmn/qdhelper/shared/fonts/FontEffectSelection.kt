package com.xiyunmn.qdhelper.shared.fonts

import android.content.Context
import android.graphics.Typeface
import com.xiyunmn.qdhelper.config.FontEffectScope
import java.io.File

val FontRole.effectScope: FontEffectScope get() = when (this) {
    FontRole.READER -> FontEffectScope.Reader
    FontRole.GLOBAL_REGULAR -> FontEffectScope.Regular
    FontRole.GLOBAL_EMPHASIS -> FontEffectScope.Emphasis
}

fun FontEffectScope.fontRole(): FontRole = when (this) {
    FontEffectScope.Reader -> FontRole.READER
    FontEffectScope.Regular -> FontRole.GLOBAL_REGULAR
    FontEffectScope.Emphasis -> FontRole.GLOBAL_EMPHASIS
}

data class FontEffectSelection(val path: String, val style: Int? = null)

fun fontEffectSelection(context: Context, role: FontRole): FontEffectSelection {
    val path = FontSelectionStore.selectedPath(context, role)
    return if (role == FontRole.GLOBAL_EMPHASIS && (path.isBlank() || !FontLibraryStore.validateTypeface(File(path)))) {
        FontEffectSelection(FontSelectionStore.selectedPath(context, FontRole.GLOBAL_REGULAR), Typeface.BOLD)
    } else FontEffectSelection(path)
}
