package com.xiyunmn.qdhelper.settings.api

import android.content.Context
import android.graphics.Typeface

/** Font samples are read-only and loaded only for the selected option, off the UI thread. */
interface SettingsFontChoiceController : SettingsDynamicChoiceController {
    val defaultTitle: String
    val defaultSummary: String
    fun preview(context: Context, key: String?): SettingsFontPreview
}

data class SettingsFontPreview(val typeface: Typeface? = null, val message: String? = null)
