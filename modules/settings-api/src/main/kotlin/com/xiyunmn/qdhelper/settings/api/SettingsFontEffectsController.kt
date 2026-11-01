package com.xiyunmn.qdhelper.settings.api

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import com.xiyunmn.qdhelper.config.FontEffectScope
import com.xiyunmn.qdhelper.config.FontEffectSettings

data class SettingsFontEffectSource(
    val values: FontEffectSettings,
    val name: String,
    val typeface: Typeface? = null,
    val message: String? = null,
    val available: Boolean = false,
)

interface SettingsFontEffectsController : SettingsToolController {
    /** Font loading and persistence run on the caller's worker dispatcher. */
    fun load(context: Context, scope: FontEffectScope): SettingsFontEffectSource
    fun save(context: Context, scope: FontEffectScope, values: FontEffectSettings): Boolean
    fun addChangedListener(listener: () -> Unit): AutoCloseable
    override fun read(context: Context) = SettingsToolState()
    override fun execute(activity: Activity, command: String, values: Map<String, String>,
        result: (SettingsToolResult) -> Unit): AutoCloseable? = null
}
