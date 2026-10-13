package com.xiyunmn.qdhelper.feature.bookshelf

import com.xiyunmn.qdhelper.feature.api.runtime.FeatureResources
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.DailyReadingDataRuntime
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.feature.api.runtime.NativeNetBridgeRuntime
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

object DailyReadingGameAdCleaner {
    private val hitCount = AtomicLong(0L)

    init {
        FeatureResources.register("DailyReadingGameAdCleaner") {
            hitCount.set(0L)
            FeatureHooks.i("Daily reading game ad cleaner hot reload state cleared")
        }
    }

    fun install(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
    ): Int {
        var installedHooks = 0
        DailyReadingDataRuntime.register("daily-reading-game-ad", priority = 100) { response ->
            if (SettingsRuntime.isDailyReadingGameAdHidden(HostSessionStore.contextOrNull())) {
                filterString(response, "methodCall.getDailyReadingData", null)
            } else {
                response
            }
        }
        installedHooks += DailyReadingDataRuntime.install(module, classLoader, phase)
        NativeNetBridgeRuntime.register("daily-reading-game-ad", priority = 100) { path, response ->
            if (SettingsRuntime.isDailyReadingGameAdHidden(HostSessionStore.contextOrNull())) {
                filterString(response, "netBridge", path)
            } else {
                response
            }
        }
        installedHooks += NativeNetBridgeRuntime.install(module, classLoader, phase)
        if (installedHooks == 0) {
            FeatureHooks.w("Daily reading game ad cleaner skipped: no target hook installed, phase=$phase")
        }
        return installedHooks
    }

    private fun filterString(raw: String, source: String, path: String?): String {
        val filtered = filterDailyReadingGameAds(raw, path)
        if (filtered.removed > 0) {
            val count = hitCount.incrementAndGet()
            if (count <= 20L || count % 50L == 0L) {
                FeatureHooks.i(
                    "P2 hit ui.dailyReading.gameAd#$count: action=filter-json, " +
                        "source=$source, path=${path.safePathLabel()}, removed=${filtered.removed}",
                )
            }
        }
        return filtered.json
    }

    private fun String?.safePathLabel(): String {
        return this?.take(96)?.replace(Regex("[^A-Za-z0-9_./?-]"), "_") ?: "-"
    }
}

internal data class DailyReadingGameAdFilterResult(
    val json: String,
    val removed: Int,
)

internal fun filterDailyReadingGameAds(raw: String, path: String? = null): DailyReadingGameAdFilterResult {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) {
        return DailyReadingGameAdFilterResult(raw, 0)
    }
    if (isGameAdvertisementPath(path)) {
        return DailyReadingGameAdFilterResult("", 1)
    }
    if (path != null && !isDailyReadingPayloadPath(path)) {
        return DailyReadingGameAdFilterResult(raw, 0)
    }
    if (!mightContainGameAdPayload(raw)) {
        return DailyReadingGameAdFilterResult(raw, 0)
    }
    return runCatching {
        when (trimmed.first()) {
            '{' -> {
                val root = JSONObject(raw)
                val removed = removeDailyReadingGameAds(root)
                DailyReadingGameAdFilterResult(if (removed > 0) root.toString() else raw, removed)
            }
            '[' -> {
                val root = JSONArray(raw)
                val removed = removeDailyReadingGameAds(root)
                DailyReadingGameAdFilterResult(if (removed > 0) root.toString() else raw, removed)
            }
            else -> DailyReadingGameAdFilterResult(raw, 0)
        }
    }.getOrElse {
        DailyReadingGameAdFilterResult(raw, 0)
    }
}

private fun removeDailyReadingGameAds(obj: JSONObject): Int {
    var removed = 0
    val keys = obj.keys().asSequence().toList()
    keys.forEach { key ->
        removed += when (val value = obj.opt(key)) {
            is JSONObject -> {
                if (isDailyReadingGameAd(value, dailyReadingArray = false, fieldName = key)) {
                    obj.remove(key)
                    1
                } else {
                    removeDailyReadingGameAds(value)
                }
            }
            is JSONArray -> removeDailyReadingGameAds(value)
            else -> 0
        }
    }
    return removed
}

private fun removeDailyReadingGameAds(array: JSONArray): Int {
    var removed = 0
    val dailyReadingArray = (0 until array.length())
        .mapNotNull { index -> array.optJSONObject(index) }
        .any(::looksLikeDailyReadingItem)

    for (index in array.length() - 1 downTo 0) {
        val obj = array.optJSONObject(index) ?: continue
        if (isDailyReadingGameAd(obj, dailyReadingArray = dailyReadingArray, fieldName = null)) {
            array.remove(index)
            removed++
        }
    }

    for (index in 0 until array.length()) {
        removed += when (val value = array.opt(index)) {
            is JSONObject -> removeDailyReadingGameAds(value)
            is JSONArray -> removeDailyReadingGameAds(value)
            else -> 0
        }
    }
    return removed
}

private fun isDailyReadingGameAd(
    obj: JSONObject,
    dailyReadingArray: Boolean,
    fieldName: String?,
): Boolean {
    if (hasExplicitGameAdText(obj)) {
        return true
    }
    if (hasStrongGameAdShape(obj)) {
        return true
    }
    val type = jsonInt(obj, "Type", "type")
    if (type == 1 && (dailyReadingArray || GAME_AD_KEYS.any(obj::has) || fieldNameLooksLikeGameAd(fieldName))) {
        return true
    }
    return false
}

private fun jsonInt(obj: JSONObject, vararg keys: String): Int? {
    keys.forEach { key ->
        if (obj.has(key)) {
            return obj.optInt(key)
        }
    }
    return null
}

private fun looksLikeDailyReadingItem(obj: JSONObject): Boolean {
    return DAILY_READING_ITEM_KEYS.any(obj::has)
}

private fun hasStrongGameAdShape(obj: JSONObject): Boolean {
    if (obj.has("ADImage") || obj.has("ADText") || obj.has("PositionMark")) {
        return true
    }
    val keys = obj.keys().asSequence().map { it.lowercase() }.toSet()
    val hasImage = keys.any { key -> key.contains("adimage") || key.contains("adpic") || key.contains("adurl") }
    val hasAction = keys.any { key -> key.contains("actionurl") || key.contains("jumpurl") || key.contains("linkurl") }
    val hasGame = keys.any { key -> key.contains("game") }
    return hasImage && (hasAction || hasGame)
}

private fun hasExplicitGameAdText(obj: JSONObject): Boolean {
    val text = shallowObjectText(obj)
    if (GAME_AD_TEXT_MARKERS.any(text::contains)) {
        return true
    }
    val lower = text.lowercase()
    return (lower.contains("gamecenter") || lower.contains("qdgamecenter")) &&
        (lower.contains("advertisement") || lower.contains("adimage") || lower.contains("adurl"))
}

private fun shallowObjectText(obj: JSONObject): String {
    val builder = StringBuilder()
    obj.keys().asSequence().forEach { key ->
        builder.append(key).append('=')
        when (val value = obj.opt(key)) {
            is JSONObject, is JSONArray -> builder.append(value.javaClass.simpleName)
            else -> builder.append(value)
        }
        builder.append(';')
    }
    return builder.toString()
}

private fun fieldNameLooksLikeGameAd(fieldName: String?): Boolean {
    val normalized = fieldName?.lowercase() ?: return false
    return GAME_AD_FIELD_MARKERS.any(normalized::contains)
}

private fun isGameAdvertisementPath(path: String?): Boolean {
    val normalized = normalizedPath(path) ?: return false
    return normalized in GAME_AD_PATHS
}

private fun isDailyReadingPayloadPath(path: String): Boolean {
    return normalizedPath(path) in DAILY_READING_PAYLOAD_PATHS
}

private fun normalizedPath(path: String?): String? {
    return path
        ?.substringBefore('?')
        ?.trimEnd('/')
        ?.lowercase()
        ?.takeIf(String::isNotEmpty)
}

private fun mightContainGameAdPayload(raw: String): Boolean {
    return GAME_AD_FAST_MARKERS.any(raw::contains) ||
        JSON_TYPE_ONE_PATTERN.containsMatchIn(raw)
}

private val GAME_AD_KEYS = setOf(
    "ADImage",
    "ADText",
    "Extra",
    "PositionMark",
)

private val GAME_AD_TEXT_MARKERS = listOf(
    "游戏广告",
    "惊喜活动推荐",
    "更多游戏",
)

private val GAME_AD_FIELD_MARKERS = listOf(
    "ad",
    "advert",
    "gamead",
    "game_ad",
    "advertisement",
)

private val GAME_AD_PATHS = setOf(
    "/qdgamecenter/gameinfo/getadvertisement",
)

private val DAILY_READING_PAYLOAD_PATHS = setOf(
    "/argus/api/v2/dailyrecommend/getdailyrecommend",
    "/argus/api/v1/adv/getadvlist",
)

private val JSON_TYPE_ONE_PATTERN = Regex("\"[Tt]ype\"\\s*:\\s*\"?1\"?")

private val GAME_AD_FAST_MARKERS = listOf(
    "ADImage",
    "ADText",
    "PositionMark",
    "ActionUrl",
    "adImage",
    "adText",
    "adurl",
    "adUrl",
    "advertisement",
    "gamecenter",
    "qdgamecenter",
) + GAME_AD_TEXT_MARKERS

private val DAILY_READING_ITEM_KEYS = setOf(
    "ActionType",
    "ADImage",
    "ADText",
    "AlgInfo",
    "Author",
    "AuthorName",
    "BookId",
    "BookIntro",
    "BookName",
    "BookStatus",
    "BusinessTag",
    "CategoryName",
    "Description",
    "Extra",
    "LastChapterId",
    "LastChapterName",
    "OutPageIndex",
    "PositionMark",
    "ShowTagList",
    "ShowTime",
    "Sp",
    "SubCategoryName",
    "WordCount",
    "WordsCount",
)
