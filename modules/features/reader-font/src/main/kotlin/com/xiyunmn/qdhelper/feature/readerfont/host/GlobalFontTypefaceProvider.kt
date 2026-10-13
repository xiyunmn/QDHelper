package com.xiyunmn.qdhelper.feature.readerfont.host

import android.content.Context
import android.graphics.Typeface
import android.widget.TextView
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.shared.fonts.FontLibraryStore
import com.xiyunmn.qdhelper.shared.fonts.FontRole
import com.xiyunmn.qdhelper.shared.fonts.FontSelectionStore
import com.xiyunmn.qdhelper.shared.fonts.FontTypefaceCache
import java.io.File
import java.util.Locale

internal fun flutterFontRole(requestedFamily: String?): FontRole {
    val normalized = requestedFamily.orEmpty().lowercase(Locale.US)
    return if ("medium" in normalized || "bold" in normalized) {
        FontRole.GLOBAL_EMPHASIS
    } else {
        FontRole.GLOBAL_REGULAR
    }
}

internal object GlobalFontTypefaceProvider {
    fun regular(context: Context): Typeface? {
        if (!SettingsRuntime.isCustomGlobalFontEnabled(context)) return null
        return FontTypefaceCache.load(FontSelectionStore.selectedPath(context, FontRole.GLOBAL_REGULAR))
    }

    fun emphasis(context: Context): Typeface? {
        if (!SettingsRuntime.isCustomGlobalFontEnabled(context)) return null
        val explicit = FontSelectionStore.selectedPath(context, FontRole.GLOBAL_EMPHASIS)
        if (explicit.isNotBlank()) {
            FontTypefaceCache.load(explicit)?.let { return it }
        }
        val regular = FontSelectionStore.selectedPath(context, FontRole.GLOBAL_REGULAR)
        return FontTypefaceCache.load(regular, Typeface.BOLD)
    }

    fun flutterFontPath(context: Context, requestedFamily: String?): String? {
        if (!SettingsRuntime.isCustomGlobalFontEnabled(context)) return null
        val role = flutterFontRole(requestedFamily)
        val candidates = buildList {
            add(FontSelectionStore.selectedPath(context, role))
            if (role == FontRole.GLOBAL_EMPHASIS) {
                add(FontSelectionStore.selectedPath(context, FontRole.GLOBAL_REGULAR))
            }
        }
        return candidates.asSequence()
            .filter(String::isNotBlank)
            .map(::File)
            .firstOrNull(FontLibraryStore::validateTypeface)
            ?.let { FontLibraryStore.normalizePath(it.path) }
    }

    fun apply(textView: TextView, style: Int) {
        val typeface = if (style and Typeface.BOLD != 0) emphasis(textView.context) else regular(textView.context)
        if (typeface != null) {
            textView.typeface = typeface
        }
    }

    fun isApplied(textView: TextView): Boolean {
        val current = textView.typeface ?: return false
        return current == regular(textView.context) || current == emphasis(textView.context)
    }
}
