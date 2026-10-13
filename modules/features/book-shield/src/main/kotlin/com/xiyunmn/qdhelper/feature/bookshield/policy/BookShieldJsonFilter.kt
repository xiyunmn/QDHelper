package com.xiyunmn.qdhelper.feature.bookshield.policy

import com.xiyunmn.qdhelper.feature.bookshield.model.BookInfo
import com.xiyunmn.qdhelper.feature.bookshield.model.categoryForStatistics
import com.xiyunmn.qdhelper.feature.bookshield.stats.BookShieldStats
import com.xiyunmn.qdhelper.feature.bookshield.stats.ShieldEvent
import org.json.JSONArray
import org.json.JSONObject

/**
 * Rule-based removal of book nodes inside a Flutter bridge JSON payload. The book
 * store / category / library pages in 起点 are Flutter and fetch their book lists as
 * JSON through the native net bridge; that JSON *does* carry BookName / AuthorName /
 * CategoryName / BookId, so filtering it here shields by name, author, category, word
 * count and book-id even where the native card models (e.g. MorphingItem) have no
 * author. Best-effort: any parse issue returns the original string unchanged.
 */
internal object BookShieldJsonFilter {
    data class Result(val json: String, val removed: Int)

    fun filter(raw: String, rules: BookShieldRules, location: String = BookShieldLocations.FLUTTER_BOOK_STREAM): Result {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || !rules.hasRules || !mightContainBooks(raw)) {
            return Result(raw, 0)
        }
        return runCatching {
            when (trimmed.first()) {
                '{' -> {
                    val root = JSONObject(raw)
                    val removed = walkObject(root, rules, location)
                    Result(if (removed > 0) root.toString() else raw, removed)
                }
                '[' -> {
                    val root = JSONArray(raw)
                    val removed = walkArray(root, rules, location)
                    Result(if (removed > 0) root.toString() else raw, removed)
                }
                else -> Result(raw, 0)
            }
        }.getOrElse { Result(raw, 0) }
    }

    private fun mightContainBooks(raw: String): Boolean {
        return raw.contains("BookName") ||
            raw.contains("bookName") ||
            raw.contains("ItemName") ||
            raw.contains("itemName") ||
            raw.contains("\"Title\"") ||
            raw.contains("\"title\"") ||
            raw.contains("BookId") ||
            raw.contains("bookId") ||
            raw.contains("AuthorName") ||
            raw.contains("\"BId\"") ||
            raw.contains("\"CBID\"") ||
            raw.contains("\"CbId\"") ||
            raw.contains("\"cbId\"")
    }

    private fun walkObject(obj: JSONObject, rules: BookShieldRules, location: String): Int {
        var removed = 0
        obj.keys().asSequence().toList().forEach { key ->
            when (val value = obj.opt(key)) {
                is JSONArray -> removed += walkArray(value, rules, location)
                is JSONObject -> removed += walkObject(value, rules, location)
            }
        }
        return removed
    }

    private fun walkArray(array: JSONArray, rules: BookShieldRules, location: String): Int {
        var removed = 0
        for (index in array.length() - 1 downTo 0) {
            val obj = array.optJSONObject(index) ?: continue
            if (!isBookNode(obj)) {
                continue
            }
            val info = bookInfoFromJson(obj) ?: continue
            val reason = rules.matchReason(info)
            if (reason != null) {
                array.remove(index)
                removed++
                BookShieldStats.record(
                    ShieldEvent(
                        timestamp = System.currentTimeMillis(),
                        location = location,
                        reasonKind = reason.kind,
                        reasonDetail = reason.detail,
                        bookId = info.id,
                        title = info.title,
                        author = info.author,
                        category = info.categoryForStatistics(),
                        tags = info.tags,
                        words = info.words,
                        source = info.sourceClass,
                    ),
                )
            }
        }
        for (index in 0 until array.length()) {
            when (val value = array.opt(index)) {
                is JSONObject -> removed += walkObject(value, rules, location)
                is JSONArray -> removed += walkArray(value, rules, location)
            }
        }
        return removed
    }

    private fun isBookNode(obj: JSONObject): Boolean {
        val hasTitle = obj.has("BookName") || obj.has("bookName") || obj.has("ItemName") ||
            obj.has("itemName") || obj.has("Title") || obj.has("title")
        val hasId = obj.has("BookId") || obj.has("bookId") || obj.has("BId") || obj.has("Bid") ||
            obj.has("CBID") || obj.has("CbId") || obj.has("cbId")
        return hasTitle && hasId
    }

    private fun bookInfoFromJson(obj: JSONObject): BookInfo? {
        val title = firstString(obj, "BookName", "bookName", "ItemName", "itemName", "Title", "title")
        val author = firstString(obj, "AuthorName", "Author", "authorName", "author")
        val category = firstString(obj, "CategoryName", "categoryName", "BookCategoryName", "bookCategoryName")
        val subCategory = firstString(obj, "SubCategoryName", "subCategoryName")
        val tags = firstTagString(obj, "TagName", "tagName", "Tags", "tags", "BookTags", "bookTags", "TagList", "tagList")
        val words = firstLong(obj, "WordsCount", "WordCount", "wordsCount", "wordCount", "WordsCnt", "wordsCnt", "words")
        val id = firstLong(obj, "BookId", "bookId", "BId", "Bid", "CBID", "CbId", "cbId")
        if (title.isNullOrBlank() && author.isNullOrBlank() && id == null) {
            return null
        }
        return BookInfo(
            id = id,
            title = title,
            author = author,
            category = category,
            subCategory = subCategory,
            tags = tags,
            type = null,
            typeCode = null,
            words = words,
            sourceClass = "flutter-json",
        )
    }

    private fun firstString(obj: JSONObject, vararg keys: String): String? {
        keys.forEach { key ->
            if (obj.has(key)) {
                val value = obj.optString(key, "").trim()
                if (value.isNotEmpty() && value != "null") {
                    return value
                }
            }
        }
        return null
    }

    private fun firstLong(obj: JSONObject, vararg keys: String): Long? {
        keys.forEach { key ->
            if (obj.has(key)) {
                when (val value = obj.opt(key)) {
                    is Number -> return value.toLong()
                    is String -> value.trim().toLongOrNull()?.let { return it }
                }
            }
        }
        return null
    }

    private fun firstTagString(obj: JSONObject, vararg keys: String): String? {
        keys.forEach { key ->
            if (!obj.has(key)) return@forEach
            val values = jsonTagValues(obj.opt(key))
            if (values.isNotEmpty()) return values.distinct().joinToString("、")
        }
        return null
    }

    private fun jsonTagValues(value: Any?): List<String> {
        return when (value) {
            null, JSONObject.NULL -> emptyList()
            is String -> listOf(value.trim()).filter(String::isNotEmpty)
            is JSONArray -> buildList {
                for (index in 0 until value.length()) addAll(jsonTagValues(value.opt(index)))
            }
            is JSONObject -> listOfNotNull(
                firstString(value, "Name", "name", "TagName", "tagName", "Label", "label"),
            )
            else -> emptyList()
        }
    }
}
