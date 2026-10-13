package com.xiyunmn.qdhelper.feature.diagnostics.identity

import org.json.JSONArray
import org.json.JSONObject

/**
 * Structure-preserving, value-redacting summary for identity payloads.
 *
 * Device fingerprint payloads (OStar/Beacon) are JSON documents whose *keys* are the
 * research signal ("which slot changed between runs") while the *values* are raw device
 * identifiers that must never reach logcat or the on-device log file. This summary keeps
 * every key path and replaces each scalar with a stable one-way fingerprint, except for
 * enumerable safe flags (`0`/`1`/`true`/`false`) which stay readable.
 */
internal object JsonFingerprintSummary {
    private const val MAX_DEPTH = 6
    private const val MAX_ENTRIES = 48

    /**
     * Returns `{key=summary,...}` for a JSON object/array payload, or a plain fingerprint
     * when the payload is not JSON. Never returns any raw value that is not a safe flag.
     */
    fun summarize(payload: String?): String {
        if (payload.isNullOrEmpty()) return "empty"
        val parsed = parse(payload) ?: return IdentityEvidenceCodec.fingerprint(payload)
        val entries = linkedMapOf<String, String>()
        val truncated = walk(parsed, path = "", depth = 0, entries = entries)
        val body = entries.entries.joinToString(",") { (key, value) -> "$key=$value" }
        val suffix = if (truncated) ",…truncated" else ""
        return "len=${payload.length},fields={$body$suffix}"
    }

    private fun parse(payload: String): Any? {
        val trimmed = payload.trim()
        return when {
            trimmed.startsWith("{") -> runCatching { JSONObject(trimmed) }.getOrNull()
            trimmed.startsWith("[") -> runCatching { JSONArray(trimmed) }.getOrNull()
            else -> null
        }
    }

    /** Returns true when the walk stopped early because a limit was reached. */
    private fun walk(value: Any?, path: String, depth: Int, entries: MutableMap<String, String>): Boolean {
        if (entries.size >= MAX_ENTRIES) return true
        if (depth > MAX_DEPTH) {
            entries[path.ifEmpty { "root" }] = "depth_limit"
            return true
        }
        return when (value) {
            is JSONObject -> {
                var truncated = false
                val keys = value.keys()
                while (keys.hasNext()) {
                    if (entries.size >= MAX_ENTRIES) return true
                    val key = keys.next()
                    val childPath = if (path.isEmpty()) key else "$path.$key"
                    if (walk(value.opt(key), childPath, depth + 1, entries)) truncated = true
                }
                truncated
            }

            is JSONArray -> {
                var truncated = false
                for (index in 0 until value.length()) {
                    if (entries.size >= MAX_ENTRIES) return true
                    if (walk(value.opt(index), "$path[$index]", depth + 1, entries)) truncated = true
                }
                truncated
            }

            else -> {
                entries[path.ifEmpty { "root" }] = summarizeScalar(value)
                false
            }
        }
    }

    /**
     * Scalars go through [IdentityEvidenceCodec.safeFlag], which keeps enumerable flags readable
     * and hashes everything else. Hashing (rather than dropping) preserves the only property the
     * research needs from a value: whether it changed between runs.
     */
    private fun summarizeScalar(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is Boolean -> value.toString()
        is Number -> IdentityEvidenceCodec.safeFlag(value.toString())
        is String -> IdentityEvidenceCodec.safeFlag(value)
        else -> value.javaClass.simpleName
    }
}
