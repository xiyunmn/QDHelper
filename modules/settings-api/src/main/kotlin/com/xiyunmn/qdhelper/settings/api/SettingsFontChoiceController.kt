package com.xiyunmn.qdhelper.settings.api

import android.content.Context
import android.graphics.Typeface

/** Font previews are read-only; callers load visible options off the UI thread. */
interface SettingsFontPreviewController {
    fun preview(context: Context, key: String?): SettingsFontPreview
    fun addChangedListener(listener: () -> Unit): AutoCloseable
}

interface SettingsFontChoiceController : SettingsDynamicChoiceController, SettingsFontPreviewController {
    val defaultTitle: String
    val defaultSummary: String
}

interface SettingsFontLibraryController : SettingsManagedListController, SettingsFontPreviewController {
    fun deleteSelected(context: Context, keys: List<String>, shouldContinue: () -> Boolean): SettingsFontDeleteResult
}

data class SettingsFontDeleteResult(val removedKeys: Set<String>, val message: String)

data class SettingsFontPreview(val typeface: Typeface? = null, val message: String? = null)
