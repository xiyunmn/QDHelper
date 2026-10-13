package com.xiyunmn.qdhelper.feature.readerfont.host

import android.content.Context
import android.graphics.Typeface
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.shared.fonts.FontRole
import com.xiyunmn.qdhelper.shared.fonts.FontSelectionStore
import com.xiyunmn.qdhelper.shared.fonts.FontTypefaceCache

internal object CustomReaderFontTypefaceProvider {
    fun currentTypeface(): Typeface? {
        val context = HostSessionStore.contextOrNull() ?: return null
        return currentTypeface(context)
    }

    fun currentTypeface(context: Context): Typeface? {
        if (!FontSelectionStore.isEnabled(context, FontRole.READER)) return null
        return FontTypefaceCache.load(FontSelectionStore.selectedPath(context, FontRole.READER))
    }

    fun clearCache() {
        FontTypefaceCache.clear()
    }
}
