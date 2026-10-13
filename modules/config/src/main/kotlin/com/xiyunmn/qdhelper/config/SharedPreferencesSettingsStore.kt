package com.xiyunmn.qdhelper.config

import android.content.SharedPreferences

class SharedPreferencesSettingsStore(
    private val preferences: SharedPreferences,
) {
    fun observe(keys: Set<String>, changed: () -> Unit): AutoCloseable {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key in keys) changed()
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return AutoCloseable { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun <T : Any> get(key: SettingKey<T>): T {
        val value: Any = when (key.valueType) {
            Boolean::class -> preferences.getBoolean(key.rawKey, key.defaultValue as Boolean)
            String::class -> preferences.getString(key.rawKey, key.defaultValue as String) ?: key.defaultValue
            Long::class -> preferences.getLong(key.rawKey, key.defaultValue as Long)
            else -> error("Unsupported setting type ${key.valueType} for ${key.rawKey}")
        }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    fun <T : Any> put(key: SettingKey<T>, value: T): Boolean {
        if (!key.validate(value)) return false
        val editor = preferences.edit()
        when (key.valueType) {
            Boolean::class -> editor.putBoolean(key.rawKey, value as Boolean)
            String::class -> editor.putString(key.rawKey, value as String)
            Long::class -> editor.putLong(key.rawKey, value as Long)
            else -> error("Unsupported setting type ${key.valueType} for ${key.rawKey}")
        }
        return editor.commit()
    }

    fun contains(rawKey: String): Boolean = preferences.contains(rawKey)

    fun removeRaw(rawKeys: Collection<String>): Boolean {
        val editor = preferences.edit()
        rawKeys.forEach(editor::remove)
        return editor.commit()
    }

    fun readRawBoolean(rawKey: String, defaultValue: Boolean): Boolean =
        preferences.getBoolean(rawKey, defaultValue)

    fun readRawString(rawKey: String, defaultValue: String): String =
        preferences.getString(rawKey, defaultValue) ?: defaultValue

    fun readRawLong(rawKey: String, defaultValue: Long): Long =
        preferences.getLong(rawKey, defaultValue)

    fun writeRawBoolean(rawKey: String, value: Boolean): Boolean =
        preferences.edit().putBoolean(rawKey, value).commit()

    fun writeRawString(rawKey: String, value: String): Boolean =
        preferences.edit().putString(rawKey, value).commit()

    fun writeRawLong(rawKey: String, value: Long): Boolean =
        preferences.edit().putLong(rawKey, value).commit()

    fun putDefaults(keys: Collection<SettingKey<*>>): Boolean {
        if (keys.isEmpty()) return true
        val editor = preferences.edit()
        keys.forEach { key ->
            when (key.valueType) {
                Boolean::class -> editor.putBoolean(key.rawKey, key.defaultValue as Boolean)
                String::class -> editor.putString(key.rawKey, key.defaultValue as String)
                Long::class -> editor.putLong(key.rawKey, key.defaultValue as Long)
                else -> error("Unsupported setting type ${key.valueType} for ${key.rawKey}")
            }
        }
        return editor.commit()
    }

    fun putRaw(values: Map<String, Any>): Boolean {
        val editor = preferences.edit()
        values.forEach { (key, value) ->
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is String -> editor.putString(key, value)
                is Long -> editor.putLong(key, value)
                else -> error("Unsupported raw setting type for $key")
            }
        }
        return editor.commit()
    }
}
