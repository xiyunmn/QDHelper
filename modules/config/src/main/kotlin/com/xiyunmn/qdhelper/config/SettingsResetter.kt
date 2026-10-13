package com.xiyunmn.qdhelper.config

import android.content.Context
import com.xiyunmn.qdhelper.config.schema.SettingSchemaCatalog

internal object SettingsResetter {
    fun resetP0Defaults(context: Context): Boolean =
        resetDefaults(
            context,
            SettingSchemaCatalog.settingKeys
                .filter { it.scope != SettingScope.RUNTIME }
                .map { it.rawKey },
        )

    fun resetDefaults(context: Context, keys: Collection<String>): Boolean {
        val distinctKeys = keys.distinct()
        ConfigRuntime.initialize(context)
        return ConfigRuntime.resetDefaults(distinctKeys)
    }
}
