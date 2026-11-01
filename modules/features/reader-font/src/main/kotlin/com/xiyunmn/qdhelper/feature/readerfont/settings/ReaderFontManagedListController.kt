package com.xiyunmn.qdhelper.feature.readerfont.settings

import android.app.Activity
import android.content.Context
import com.xiyunmn.qdhelper.feature.readerfont.ui.CustomReaderFontPanelInjector
import com.xiyunmn.qdhelper.settings.api.SettingsManagedListActionResult
import com.xiyunmn.qdhelper.settings.api.SettingsFontLibraryController
import com.xiyunmn.qdhelper.settings.api.SettingsFontDeleteResult
import com.xiyunmn.qdhelper.settings.api.SettingsFontPreview
import com.xiyunmn.qdhelper.settings.api.SettingsManagedListItem
import com.xiyunmn.qdhelper.shared.fonts.FontLibraryChanges
import com.xiyunmn.qdhelper.shared.fonts.FontLibraryStore
import com.xiyunmn.qdhelper.shared.fonts.FontRole
import com.xiyunmn.qdhelper.shared.fonts.FontSelectionStore
import com.xiyunmn.qdhelper.shared.fonts.FontTypefaceCache
import com.xiyunmn.qdhelper.shared.fonts.displayLabel
import com.xiyunmn.qdhelper.shared.fonts.isUsable
import java.io.File

internal object ReaderFontManagedListController : SettingsFontLibraryController {
    override fun preview(context: Context, key: String?): SettingsFontPreview {
        val face = key?.let { FontTypefaceCache.load(it) }
        return SettingsFontPreview(face, if (face == null) "字体预览不可用" else null)
    }

    override fun items(context: Context): List<SettingsManagedListItem> {
        return FontLibraryStore.readFontEntries(context).map { entry ->
            val path = entry.path
            val roles = FontSelectionStore.selectedRolesForPath(context, path)
            val note = buildList {
                if (!entry.managed) add("外部文件")
                if (!entry.state.isUsable) add(entry.state.displayLabel())
            }.joinToString(" · ").takeIf(String::isNotBlank)
            SettingsManagedListItem(
                key = path,
                title = FontLibraryStore.displayNameForPath(path),
                summary = File(path).name,
                active = roles.isNotEmpty(),
                badge = roles.joinToString(" / ") { role ->
                    when (role) {
                        FontRole.READER -> "阅读"
                        FontRole.GLOBAL_REGULAR -> "常规"
                        FontRole.GLOBAL_EMPHASIS -> "强调"
                    }
                }.takeIf(String::isNotBlank),
                note = note,
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

    override fun deleteSelected(context: Context, keys: List<String>, shouldContinue: () -> Boolean): SettingsFontDeleteResult {
        val requested = keys.map(FontLibraryStore::normalizePath).filter(String::isNotBlank).distinct()
        val known = FontLibraryStore.readFontEntries(context).mapTo(hashSetOf()) { it.path }
        val removed = linkedSetOf<String>()
        var deleted = 0
        var missing = 0
        var pendingCleanup = 0
        for (key in requested) {
            if (!shouldContinue()) break
            if (key !in known) {
                removed += key
                missing++
                continue
            }
            val result = runCatching { FontLibraryStore.deleteFont(context, key) }.getOrNull()
            if (result?.settingsUpdated == true) {
                removed += key
                deleted++
                if (!result.fileDeleted) pendingCleanup++
            }
        }
        val remaining = requested.size - removed.size
        val message = buildList {
            if (deleted > 0) add("已删除 $deleted 款字体")
            if (missing > 0) add("$missing 款已不在字体库")
            if (remaining > 0) add("$remaining 款未删除，可重试")
            if (pendingCleanup > 0) add("$pendingCleanup 款文件待清理")
        }.joinToString("；").ifBlank { "没有需要删除的字体" }
        return SettingsFontDeleteResult(removed, message)
    }
}
