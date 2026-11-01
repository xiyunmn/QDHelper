package com.xiyunmn.qdhelper.feature.readerfont.host

import android.content.Context
import android.graphics.Typeface
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.feature.readerfont.hook.NativeFontEffects
import com.xiyunmn.qdhelper.shared.fonts.FontRole
import com.xiyunmn.qdhelper.shared.fonts.FontTypefaceCache
import com.xiyunmn.qdhelper.shared.fonts.effectScope
import com.xiyunmn.qdhelper.shared.fonts.fontEffectSelection

internal object NativeFontTypefaceProvider {
    fun load(context: Context, role: FontRole): Typeface? {
        val selection = fontEffectSelection(context, role)
        val effects = SettingsRuntime.fontEffects(role.effectScope, context)
        val face = FontTypefaceCache.load(selection.path, selection.style, effects.weight,
            isolationKey = if (effects.changesMetrics) "native:${role.name}:$effects" else null) ?: return null
        if (effects.changesMetrics) NativeFontEffects.register(face, role.effectScope, effects)
        return face
    }
}
