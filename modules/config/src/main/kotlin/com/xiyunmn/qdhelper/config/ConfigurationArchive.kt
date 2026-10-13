package com.xiyunmn.qdhelper.config

import android.content.Context
import android.content.SharedPreferences
import com.xiyunmn.qdhelper.config.schema.SettingSchemaCatalog
import org.json.JSONArray
import org.json.JSONObject

data class ConfigurationArchiveExport(
    val bytes: ByteArray,
)

data class ConfigurationArchiveImport(
    val message: String,
)

object ConfigurationArchive {
    private const val SETTINGS_FORMAT = "qdhelper-settings"
    private const val SETTINGS_VERSION = 1

    fun export(context: Context): Result<ConfigurationArchiveExport> = runCatching {
        val app = context.applicationContext ?: context
        SettingsRuntime.initialize(app)
        val preferences = SettingsRuntime.prefs(app)
        val settings = JSONObject()
        exportableKeys().forEach { key ->
            if (!preferences.contains(key.rawKey)) return@forEach
            settings.put(key.rawKey, normalizeStoredValue(key, preferences.all[key.rawKey]))
        }
        val settingsText = JSONObject()
            .put("format", SETTINGS_FORMAT)
            .put("version", SETTINGS_VERSION)
            .put("settings", settings)
            .toString(2)
        val yaml = BookShieldRuleRepository.text(app)
        val bytes = ConfigurationArchiveCodec.encode(
            ConfigurationArchiveFiles(settingsJson = settingsText, bookShieldYaml = yaml),
        )
        ConfigurationArchiveExport(bytes)
    }

    fun import(context: Context, archive: ByteArray): Result<ConfigurationArchiveImport> = runCatching {
        val app = context.applicationContext ?: context
        SettingsRuntime.initialize(app)
        val files = ConfigurationArchiveCodec.decode(archive)
        val parsedSettings = parseSettings(files.settingsJson)
        val yaml = when (val decoded = BookShieldYamlCodec.decode(files.bookShieldYaml)) {
            is BookShieldYamlResult.Success -> decoded.text
            is BookShieldYamlResult.Failure -> error("书籍屏蔽 YAML 无效：${decoded.error.displayMessage()}")
        }
        val saved = BookShieldRuleRepository.saveTextWithTransaction(app, yaml) {
            applySettingsAtomically(SettingsRuntime.prefs(app), parsedSettings)
        }
        if (saved is BookShieldRuleOperationResult.Failure) {
            error("配置写入失败：${saved.error.displayMessage()}")
        }
        ConfigurationArchiveImport(
            "已导入 ${parsedSettings.size} 项设置和书籍屏蔽规则",
        )
    }

    private fun exportableKeys(): List<SettingKey<*>> {
        return SettingSchemaCatalog.settingKeys.filter { it.scope != SettingScope.RUNTIME }
    }

    internal fun parseSettings(text: String): Map<String, Any> {
        val root = JSONObject(text)
        require(root.namesSet() == setOf("format", "version", "settings")) { "settings.json 字段无效" }
        require(root.stringField("format") == SETTINGS_FORMAT && root.longField("version") == SETTINGS_VERSION.toLong()) {
            "settings.json 版本无效"
        }
        val settings = root.optJSONObject("settings") ?: error("settings.json 缺少 settings")
        val known = exportableKeys().associateBy(SettingKey<*>::rawKey)
        val result = linkedMapOf<String, Any>()
        settings.keys().forEach { rawKey ->
            val raw = settings.get(rawKey)
            require(raw !is JSONObject && raw !is JSONArray && raw != JSONObject.NULL) { "设置值类型无效：$rawKey" }
            if (rawKey in RETIRED_BOOLEAN_IMPORT_KEYS) {
                require(raw is Boolean) { "设置值类型无效：$rawKey" }
                return@forEach
            }
            if (rawKey == "feature.chapter_export.tree_uri") {
                require(raw is String) { "设置值类型无效：$rawKey" }
                return@forEach
            }
            val key = known[rawKey] ?: error("settings.json 包含未知或不可导入设置：$rawKey")
            result[rawKey] = normalizeImportedValue(key, raw)
        }
        return result
    }

    private fun normalizeStoredValue(key: SettingKey<*>, raw: Any?): Any {
        require(raw != null) { "设置值缺失：${key.rawKey}" }
        return normalizeValue(key, raw, "本地设置值类型无效")
    }

    private fun normalizeImportedValue(key: SettingKey<*>, raw: Any): Any =
        normalizeValue(key, raw, "设置值类型无效")

    private fun normalizeValue(key: SettingKey<*>, raw: Any, label: String): Any {
        val value: Any = when (key.valueType) {
            Boolean::class -> raw as? Boolean ?: error("$label：${key.rawKey}")
            String::class -> raw as? String ?: error("$label：${key.rawKey}")
            Long::class -> when (raw) {
                is Long -> raw
                is Int -> raw.toLong()
                else -> error("$label：${key.rawKey}")
            }
            else -> error("不支持的设置类型：${key.rawKey}")
        }
        require(key.accepts(value)) { "设置值校验失败：${key.rawKey}" }
        return value
    }

    private fun applySettingsAtomically(
        preferences: SharedPreferences,
        values: Map<String, Any>,
    ): Result<Unit> {
        val before = values.keys.associateWith { key ->
            StoredPreference(preferences.contains(key), preferences.all[key])
        }
        val commitFailure = runCatching {
            val editor = preferences.edit()
            values.forEach { (key, value) -> editor.putTyped(key, value) }
            check(editor.commit()) { "commit 返回 false" }
        }.exceptionOrNull() ?: return Result.success(Unit)
        val rolledBack = runCatching {
            val rollback = preferences.edit()
            before.forEach { (key, stored) ->
                if (!stored.present) rollback.remove(key) else stored.value?.let { rollback.putTyped(key, it) }
            }
            rollback.commit()
        }.getOrDefault(false)
        val detail = commitFailure.message?.takeIf(String::isNotBlank)?.let { "：$it" }.orEmpty()
        return Result.failure(
            IllegalStateException(
                if (rolledBack) "普通设置保存失败$detail" else "普通设置保存失败$detail，且设置回滚失败",
                commitFailure,
            ),
        )
    }

    private fun SharedPreferences.Editor.putTyped(key: String, value: Any): SharedPreferences.Editor = apply {
        when (value) {
            is Boolean -> putBoolean(key, value)
            is String -> putString(key, value)
            is Long -> putLong(key, value)
            is Int -> putInt(key, value)
            is Float -> putFloat(key, value)
            else -> error("不支持的设置值类型：$key")
        }
    }

    private fun JSONObject.namesSet(): Set<String> = keys().asSequence().toSet()

    private fun JSONObject.stringField(name: String): String = get(name) as? String
        ?: error("settings.json 字段类型无效：$name")

    private fun JSONObject.longField(name: String): Long = when (val value = get(name)) {
        is Int -> value.toLong()
        is Long -> value
        else -> error("settings.json 字段类型无效：$name")
    }

    private data class StoredPreference(
        val present: Boolean,
        val value: Any?,
    )

    private val RETIRED_BOOLEAN_IMPORT_KEYS = setOf(
        "feature.welfare.ad_points.force_visible",
        "feature.welfare.guaranteed_lottery.force_visible",
        "debug.dexkit.force_priority",
        "feature.startup.optimize_chain",
        "feature.chapter_export.enabled",
    )
}
