package com.xiyunmn.qdhelper.feature.readerfont.settings

import android.content.Context
import com.xiyunmn.qdhelper.config.ConfigRuntime
import com.xiyunmn.qdhelper.config.FontEffectScope
import com.xiyunmn.qdhelper.config.FontEffectSettings
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.settings.api.SettingsFontEffectSource
import com.xiyunmn.qdhelper.settings.api.SettingsFontEffectsController
import com.xiyunmn.qdhelper.shared.fonts.FontLibraryChanges
import com.xiyunmn.qdhelper.shared.fonts.FontLibraryStore
import com.xiyunmn.qdhelper.shared.fonts.FontSelectionStore
import com.xiyunmn.qdhelper.shared.fonts.FontTypefaceCache
import com.xiyunmn.qdhelper.shared.fonts.fontEffectSelection
import com.xiyunmn.qdhelper.shared.fonts.fontRole

internal object FontEffectsController : SettingsFontEffectsController {
    override fun load(context: Context, scope: FontEffectScope): SettingsFontEffectSource {
        val values = SettingsRuntime.fontEffects(scope, context)
        val role = scope.fontRole()
        val selected = fontEffectSelection(context, role)
        if (selected.path.isBlank()) return SettingsFontEffectSource(values, "尚未选择字体",
            message = "请先在字体设置中选择共享字体")
        val face = FontTypefaceCache.load(selected.path, selected.style)
        return SettingsFontEffectSource(values, FontLibraryStore.displayNameForPath(selected.path), face,
            message = when {
                face == null -> "字体无法加载，请检查字体文件"
                !FontSelectionStore.isEnabled(context, role) -> "字体替换未开启，开启后生效"
                else -> null
            }, available = face != null)
    }

    override fun save(context: Context, scope: FontEffectScope, values: FontEffectSettings): Boolean {
        if (values != values.normalized()) return false
        if (values != FontEffectSettings() && !load(context, scope).available) return false
        ConfigRuntime.initialize(context)
        return ConfigRuntime.putString(scope.settingKey, values.encode())
    }

    override fun addChangedListener(listener: () -> Unit): AutoCloseable =
        FontLibraryChanges.addListener(listener)
}
