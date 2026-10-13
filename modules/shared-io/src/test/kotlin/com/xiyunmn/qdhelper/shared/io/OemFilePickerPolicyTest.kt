package com.xiyunmn.qdhelper.shared.io

import org.junit.Assert.assertEquals
import org.junit.Test

class OemFilePickerPolicyTest {
    @Test
    fun `prefers any OEM system handler without package mappings`() {
        val handlers = listOf(
            handler("com.android.documentsui", systemApp = true),
            handler("com.coloros.filemanager", systemApp = false),
            handler("com.android.fileexplorer", systemApp = false),
            handler("com.sec.android.app.myfiles", systemApp = false),
            handler("cn.nubia.filebrowser", systemApp = false),
            handler("com.android.filemanager", systemApp = false),
        )

        assertEquals(handlers.drop(1), OemFilePickerPolicy.preferred(handlers))
    }

    @Test
    fun `does not direct intents to third party interceptors`() {
        val handlers = listOf(
            handler("com.android.documentsui", systemApp = true),
            handler("io.example.interceptor", systemApp = false, appLabel = "Activity Interceptor"),
        )

        assertEquals(emptyList<FilePickerHandler>(), OemFilePickerPolicy.preferred(handlers))
    }

    private fun handler(
        packageName: String,
        systemApp: Boolean,
        appLabel: String = "文件管理",
    ) = FilePickerHandler(
        packageName = packageName,
        className = "$packageName.PickerActivity",
        appLabel = appLabel,
        systemApp = systemApp,
    )
}
