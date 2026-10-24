package com.xiyunmn.qdhelper.feature.readerfont.settings

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import com.xiyunmn.qdhelper.feature.readerfont.ui.CustomReaderFontPanelInjector
import com.xiyunmn.qdhelper.settings.api.SettingsFontChoiceController
import com.xiyunmn.qdhelper.settings.api.SettingsFontPreview
import com.xiyunmn.qdhelper.settings.api.SettingsDynamicChoiceItem
import com.xiyunmn.qdhelper.settings.api.SettingsManagedListActionResult
import com.xiyunmn.qdhelper.shared.fonts.FontLibraryStore
import com.xiyunmn.qdhelper.shared.fonts.FontRole
import com.xiyunmn.qdhelper.shared.fonts.FontSelectionStore
import com.xiyunmn.qdhelper.shared.fonts.FontTypefaceCache
import java.io.File

internal class FontRoleChoiceController(
    private val role: FontRole,
) : SettingsFontChoiceController {
    override val defaultTitle: String get() = when (role) {
        FontRole.READER -> "使用起点阅读字体"
        FontRole.GLOBAL_REGULAR -> "不替换界面字体"
        FontRole.GLOBAL_EMPHASIS -> "跟随界面常规字体"
    }
    override val defaultSummary: String get() = when (role) {
        FontRole.READER -> "使用阅读器中选择的字体"
        FontRole.GLOBAL_REGULAR -> "保留起点原有字体"
        FontRole.GLOBAL_EMPHASIS -> "沿用常规字体，保留强调字重"
    }

    override fun preview(context: Context, key: String?): SettingsFontPreview {
        val path = key ?: if (role == FontRole.GLOBAL_EMPHASIS) {
            FontSelectionStore.selectedPath(context, FontRole.GLOBAL_REGULAR).takeIf(String::isNotBlank)
        } else null
        if (path == null) return SettingsFontPreview()
        val face = FontTypefaceCache.load(path, if (role == FontRole.GLOBAL_EMPHASIS) Typeface.BOLD else null)
        return SettingsFontPreview(face, if (face == null) "字体无法加载，请返回字体库检查文件" else null)
    }
    override fun items(context: Context): List<SettingsDynamicChoiceItem> =
        FontLibraryStore.readFonts(context).map { path ->
            SettingsDynamicChoiceItem(
                key = FontLibraryStore.normalizePath(path),
                title = FontLibraryStore.displayNameForPath(path),
                summary = File(path).name,
            )
        }

    override fun selectedKey(context: Context): String? = FontSelectionStore.selectedPath(context, role)
        .takeIf(String::isNotBlank)
        ?.let(FontLibraryStore::normalizePath)

    override fun summary(context: Context): String {
        val selected = selectedKey(context)
        if (selected != null) {
            val name = FontLibraryStore.displayNameForPath(selected)
            return if (FontSelectionStore.isEnabled(context, role)) {
                "当前：$name"
            } else {
                "已选择：$name；开关关闭"
            }
        }
        if (role == FontRole.GLOBAL_EMPHASIS) {
            val regular = FontSelectionStore.selectedPath(context, FontRole.GLOBAL_REGULAR)
            if (regular.isNotBlank()) {
                return "跟随常规字体：${FontLibraryStore.displayNameForPath(regular)}"
            }
            return "未单独选择；有常规字体时自动跟随并保留强调字重"
        }
        return "未选择字体，请先在共享字体库中导入"
    }

    override fun select(context: Context, key: String?): SettingsManagedListActionResult {
        val saved = if (key == null) {
            FontSelectionStore.clear(context, role)
        } else {
            FontSelectionStore.select(context, role, key)
        }
        if (!saved) return SettingsManagedListActionResult(false, "保存${role.displayName}失败")
        (context as? Activity)?.let(CustomReaderFontPanelInjector::refreshVisible)
        val message = if (key == null) {
            when (role) {
                FontRole.GLOBAL_EMPHASIS -> "界面强调字体已改为跟随常规字体"
                else -> "已取消${role.displayName}替换"
            }
        } else {
            "已选择${FontLibraryStore.displayNameForPath(key)}作为${role.displayName}"
        }
        return SettingsManagedListActionResult(true, message)
    }
}
