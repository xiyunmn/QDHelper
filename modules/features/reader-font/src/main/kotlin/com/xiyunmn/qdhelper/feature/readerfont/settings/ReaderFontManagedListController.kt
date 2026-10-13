package com.xiyunmn.qdhelper.feature.readerfont.settings

import android.app.Activity
import android.content.Context
import com.xiyunmn.qdhelper.feature.readerfont.ui.CustomReaderFontPanelInjector
import com.xiyunmn.qdhelper.settings.api.SettingsManagedListActionResult
import com.xiyunmn.qdhelper.settings.api.SettingsManagedListController
import com.xiyunmn.qdhelper.settings.api.SettingsManagedListItem
import com.xiyunmn.qdhelper.shared.fonts.FontLibraryChanges
import com.xiyunmn.qdhelper.shared.fonts.FontLibraryStore
import com.xiyunmn.qdhelper.shared.fonts.FontRole
import com.xiyunmn.qdhelper.shared.fonts.FontSelectionStore
import com.xiyunmn.qdhelper.shared.fonts.displayLabel
import com.xiyunmn.qdhelper.shared.fonts.isUsable
import java.io.File

internal object ReaderFontManagedListController : SettingsManagedListController {
    override fun items(context: Context): List<SettingsManagedListItem> {
        return FontLibraryStore.readFontEntries(context).map { entry ->
            val path = entry.path
            val roles = FontSelectionStore.selectedRolesForPath(context, path)
            val detail = buildString {
                append(File(path).name)
                if (!entry.managed) append(" · 外部文件")
                if (!entry.state.isUsable) append(" · ").append(entry.state.displayLabel())
            }
            SettingsManagedListItem(
                key = path,
                title = FontLibraryStore.displayNameForPath(path),
                summary = detail,
                active = roles.isNotEmpty(),
                badge = roles.joinToString(" / ") { role ->
                    when (role) {
                        FontRole.READER -> "阅读"
                        FontRole.GLOBAL_REGULAR -> "常规"
                        FontRole.GLOBAL_EMPHASIS -> "强调"
                    }
                }.takeIf(String::isNotBlank),
            )
        }
    }

    override fun reorder(context: Context, orderedKeys: List<String>): SettingsManagedListActionResult {
        val current = FontLibraryStore.readFontEntries(context).map { it.path }
        val ordered = orderedKeys.map(FontLibraryStore::normalizePath)
        if (ordered.size != current.size || ordered.toSet() != current.toSet()) {
            return SettingsManagedListActionResult(false, "字体列表已变化，请重试")
        }
        if (!FontLibraryStore.writeFonts(context, ordered)) {
            return SettingsManagedListActionResult(false, "保存字体顺序失败")
        }
        (context as? Activity)?.let(CustomReaderFontPanelInjector::refreshVisible)
        return SettingsManagedListActionResult(true)
    }

    override fun delete(context: Context, key: String): SettingsManagedListActionResult {
        val target = FontLibraryStore.normalizePath(key)
        val known = FontLibraryStore.readFontEntries(context).any { it.path == target }
        if (!known) {
            return SettingsManagedListActionResult(false, "字体已不存在，请刷新后重试")
        }
        val result = FontLibraryStore.deleteFont(context, target)
        if (!result.settingsUpdated) {
            return SettingsManagedListActionResult(false, "删除字体失败")
        }
        (context as? Activity)?.let(CustomReaderFontPanelInjector::refreshVisible)
        val name = FontLibraryStore.displayNameForPath(target)
        return when {
            !result.fileDeleted -> SettingsManagedListActionResult(false, "已从列表移除，但字体文件删除失败")
            result.clearedRoles.isNotEmpty() -> SettingsManagedListActionResult(
                true,
                "已删除 $name，并清除${result.clearedRoles.joinToString("、") { it.displayName }}引用",
            )
            else -> SettingsManagedListActionResult(true, "已删除 $name")
        }
    }

    override fun addChangedListener(listener: () -> Unit): AutoCloseable =
        FontLibraryChanges.addListener(listener)
}
