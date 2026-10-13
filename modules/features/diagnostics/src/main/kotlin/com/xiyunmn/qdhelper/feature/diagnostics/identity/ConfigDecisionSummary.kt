package com.xiyunmn.qdhelper.feature.diagnostics.identity

import org.json.JSONArray
import org.json.JSONObject

internal object ConfigDecisionSummary {
    private const val MAX_DEPTH = 12
    private const val MAX_MATCHES = 24

    fun summarize(json: String?): String {
        val raw = IdentityEvidenceCodec.fingerprint(json)
        if (json.isNullOrEmpty()) return "raw=[$raw],decisions=none"
        val matches = linkedMapOf<String, String>()
        val error = runCatching {
            walk(JSONObject(json), path = "", depth = 0, matches = matches)
        }.exceptionOrNull()
        if (error != null) {
            return "raw=[$raw],decisions=parse_error:${error.javaClass.simpleName}"
        }
        val decisionText = matches.entries.joinToString(",") { (path, value) -> "$path=$value" }
            .ifEmpty { "none" }
        return "raw=[$raw],decisions={$decisionText}"
    }

    private fun walk(value: Any?, path: String, depth: Int, matches: MutableMap<String, String>) {
        if (depth > MAX_DEPTH || matches.size >= MAX_MATCHES) return
        when (value) {
            is JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext() && matches.size < MAX_MATCHES) {
                    val key = keys.next()
                    val childPath = if (path.isEmpty()) key else "$path.$key"
                    val child = value.opt(key)
                    if (key.lowercase() in DECISION_KEYS && child !is JSONObject && child !is JSONArray) {
                        matches[childPath] = summarizeScalar(child)
                    }
                    walk(child, childPath, depth + 1, matches)
                }
            }
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    if (matches.size >= MAX_MATCHES) break
                    walk(value.opt(index), "$path[$index]", depth + 1, matches)
                }
            }
        }
    }

    private fun summarizeScalar(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is Boolean, is Number -> value.toString()
        is String -> IdentityEvidenceCodec.safeFlag(value)
        else -> value.javaClass.simpleName
    }

    private val DECISION_KEYS = setOf(
        "isnewdevice",
        "isfreshman",
        "showlimitfreepopup",
        "isfreshmanlimitfree",
        "isnewdeviceforbubble",
        "isfreereadinguser",
        "isreceivefreereading",
        "freshdevice",
    )
}
