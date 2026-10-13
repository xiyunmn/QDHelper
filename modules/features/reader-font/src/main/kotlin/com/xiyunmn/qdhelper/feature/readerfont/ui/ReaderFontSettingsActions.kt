package com.xiyunmn.qdhelper.feature.readerfont.ui

import android.app.Activity
import com.xiyunmn.qdhelper.feature.readerfont.importflow.CustomReaderFontImporter

object ReaderFontSettingsActions {
    fun openImporter(activity: Activity) = CustomReaderFontImporter.open(activity)
    fun clear(activity: Activity) = CustomReaderFontImporter.clear(activity)
}
