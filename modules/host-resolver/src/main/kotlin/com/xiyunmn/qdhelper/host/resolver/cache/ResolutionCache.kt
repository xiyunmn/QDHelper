package com.xiyunmn.qdhelper.host.resolver.cache

import android.content.Context
import com.xiyunmn.qdhelper.host.api.target.Cardinality
import com.xiyunmn.qdhelper.host.resolver.catalog.HostTargetSpec

internal class ResolutionCache(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        if (!prefs.getBoolean(TARGET_CACHE_MIGRATION_MARKER, false)) {
            val legacyKeys = prefs.all.keys.filter { key ->
                key.startsWith("target.") && !key.startsWith("target.v2.") || key.startsWith("meta.prewarm.")
            }
            val editor = prefs.edit()
            legacyKeys.forEach(editor::remove)
            editor.putBoolean(TARGET_CACHE_MIGRATION_MARKER, true).apply()
        }
    }

    fun read(spec: HostTargetSpec, fingerprint: String): List<String>? {
        val key = key(spec, fingerprint)
        val payload = prefs.getString(key, null) ?: return null
        val values = ResolutionCacheCodec.decode(payload, spec.cardinality)
        if (values == null) {
            prefs.edit().remove(key).apply()
            return null
        }
        return values
    }

    fun write(spec: HostTargetSpec, fingerprint: String, values: List<String>): Boolean {
        val payload = ResolutionCacheCodec.encode(values, spec.cardinality) ?: return false
        prefs.edit().putString(key(spec, fingerprint), payload).apply()
        return true
    }

    fun isNegative(spec: HostTargetSpec, fingerprint: String): Boolean =
        prefs.getString(key(spec, fingerprint), null) == NEGATIVE_PAYLOAD

    fun writeNegative(spec: HostTargetSpec, fingerprint: String): Boolean {
        prefs.edit().putString(key(spec, fingerprint), NEGATIVE_PAYLOAD).apply()
        return true
    }

    fun delete(spec: HostTargetSpec, fingerprint: String) {
        prefs.edit().remove(key(spec, fingerprint)).apply()
    }

    fun readFlag(name: String, fingerprint: String): Boolean =
        prefs.getBoolean("meta.v2.$name.$fingerprint", false)

    fun writeFlag(name: String, fingerprint: String, value: Boolean): Boolean =
        prefs.edit().putBoolean("meta.v2.$name.$fingerprint", value).commit()

    fun clearLegacySettingsEntries(): Int? {
        if (prefs.getBoolean(LEGACY_CLEANUP_MARKER, false)) return null
        val legacy = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        val keys = legacy.all.keys.filter { it.startsWith(LEGACY_PREFIX) }
        if (keys.isNotEmpty()) {
            val editor = legacy.edit()
            keys.forEach(editor::remove)
            if (!editor.commit()) return 0
        }
        if (!prefs.edit().putBoolean(LEGACY_CLEANUP_MARKER, true).commit()) return 0
        return keys.size
    }

    private fun key(spec: HostTargetSpec, fingerprint: String): String =
        "target.v2.${spec.key.id.value}.r${spec.revision}.$fingerprint"

    private companion object {
        const val PREFS_NAME = "qdhelper_host_resolution_cache"
        const val NEGATIVE_PAYLOAD = "miss=true"
        const val LEGACY_PREFS_NAME = "qdhelper_settings"
        const val LEGACY_PREFIX = "runtime.dexkit.cache."
        const val LEGACY_CLEANUP_MARKER = "meta.legacy_settings_cache_cleaned.v1"
        const val TARGET_CACHE_MIGRATION_MARKER = "meta.target_cache_migrated.v2"
    }
}

internal object ResolutionCacheCodec {
    fun encode(values: List<String>, cardinality: Cardinality): String? {
        if (!accepts(cardinality, values.size) || values.any { it.isBlank() }) return null
        return buildString {
            appendLine("count=${values.size}")
            values.forEach(::appendLine)
        }
    }

    fun decode(payload: String, cardinality: Cardinality): List<String>? {
        val lines = payload.lineSequence().toList()
        val expectedCount = lines.firstOrNull()?.removePrefix("count=")?.toIntOrNull() ?: return null
        val values = lines.drop(1).filter { it.isNotBlank() }
        return values.takeIf { expectedCount == it.size && accepts(cardinality, it.size) }
    }

    private fun accepts(cardinality: Cardinality, count: Int): Boolean = when (cardinality) {
        Cardinality.One -> count == 1
        is Cardinality.Many -> count in cardinality.min..cardinality.max
    }
}
