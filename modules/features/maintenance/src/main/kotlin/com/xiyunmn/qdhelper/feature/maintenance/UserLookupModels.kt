package com.xiyunmn.qdhelper.feature.maintenance

import org.json.JSONObject
import org.json.JSONArray

internal data class UserLookupCandidate(
    val userId: Long,
    val userName: String,
)

internal object UserLookupInput {
    fun userId(raw: String): Long? = raw.trim().toLongOrNull()?.takeIf { it > 0L }
}

internal object UserLookupParser {
    fun parse(root: JSONObject): List<UserLookupCandidate> {
        val byId = linkedMapOf<Long, UserLookupCandidate>()
        val data = root.optJSONObjectIgnoreCase("Data") ?: root
        val pages = data.valueIgnoreCase("UserInfoPages", "UserInfoPage", "UserInfoList")
        collectCandidates(pages ?: data, byId, 0)
        return byId.values.toList()
    }

    private fun collectCandidates(value: Any?, byId: LinkedHashMap<Long, UserLookupCandidate>, depth: Int) {
        if (depth > 5) return
        when (value) {
            is JSONArray -> for (index in 0 until value.length()) {
                collectCandidates(value.opt(index), byId, depth + 1)
            }
            is JSONObject -> {
                val userId = positiveLong(value, "AuthorId", "UserId", "AuthorID", "UserID")
                if (userId != null) {
                    val userName = firstText(value, "AuthorName", "UserName", "NickName", "Name").orEmpty()
                    val candidate = UserLookupCandidate(userId, userName)
                    val previous = byId[userId]
                    if (previous == null || previous.userName.isBlank() && userName.isNotBlank()) {
                        byId[userId] = candidate
                    }
                }
                val keys = value.keys()
                while (keys.hasNext()) collectCandidates(value.opt(keys.next()), byId, depth + 1)
            }
        }
    }

    private fun positiveLong(item: JSONObject, vararg keys: String): Long? = keys.firstNotNullOfOrNull { key ->
        when (val value = item.valueIgnoreCase(key)) {
        is Number -> value.toLong()
        is String -> value.trim().toLongOrNull()
        else -> null
        }?.takeIf { it > 0L }
    }

    private fun firstText(item: JSONObject, vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        item.valueIgnoreCase(key)?.takeUnless { it == JSONObject.NULL }?.toString()?.trim()?.takeIf(String::isNotEmpty)
    }

    private fun JSONObject.valueIgnoreCase(vararg wanted: String): Any? {
        val names = this.keys()
        while (names.hasNext()) {
            val name = names.next()
            if (wanted.any { name.equals(it, ignoreCase = true) }) return opt(name)
        }
        return null
    }

    private fun JSONObject.optJSONObjectIgnoreCase(key: String): JSONObject? = valueIgnoreCase(key) as? JSONObject
}

internal object UserLookupSelectionPolicy {
    fun exactUserId(candidates: List<UserLookupCandidate>, userId: Long): UserLookupCandidate? =
        candidates.firstOrNull { it.userId == userId }
}
