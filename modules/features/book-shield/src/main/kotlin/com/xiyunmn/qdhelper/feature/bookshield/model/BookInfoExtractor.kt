package com.xiyunmn.qdhelper.feature.bookshield.model

import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureResources
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.math.BigDecimal
import java.math.RoundingMode

data class BookInfo(
    val id: Long?,
    val title: String?,
    val author: String?,
    val category: String?,
    val subCategory: String?,
    val tags: String?,
    val type: String?,
    val typeCode: Int?,
    val words: Long?,
    val sourceClass: String,
)

internal fun BookInfo.categoryForStatistics(): String? {
    return sequenceOf(category, subCategory, type)
        .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
        .firstOrNull { it.toDoubleOrNull() == null }
}

data class BookInfoExtractionSpec(
    val title: Boolean,
    val author: Boolean,
    val category: Boolean,
    val tags: Boolean,
    val type: Boolean,
    val id: Boolean,
    val words: Boolean,
) {
    val isComplete: Boolean
        get() = title && author && category && tags && type && id && words

    companion object {
        val ALL = BookInfoExtractionSpec(
            title = true,
            author = true,
            category = true,
            tags = true,
            type = true,
            id = true,
            words = true,
        )
    }
}

internal data class MorphingTipMetadata(
    val category: String,
    val words: Long,
)

private val MORPHING_TIP_WORDS = Regex("^([0-9]+(?:\\.[0-9]+)?)\\s*(万|亿)?\\s*(字)?$")
private val MORPHING_TIP_SEPARATOR = Regex("\\s*[·•｜|]\\s*")

/**
 * MorphingItem has no normal category/word-count getters. Book cards instead expose a display
 * tip such as "原生幻想 · 229万字" or "仙侠 · 607万". Parse only that strict structured form:
 * free-form tips (for example "2109点") and ItemDesc synopses are deliberately ignored.
 */
internal fun parseMorphingTip(value: String?): MorphingTipMetadata? {
    val parts = value?.trim()
        ?.split(MORPHING_TIP_SEPARATOR)
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        .orEmpty()
    if (parts.size < 2) return null
    val wordIndex = parts.indexOfLast { MORPHING_TIP_WORDS.matches(it) }
    if (wordIndex <= 0 || wordIndex != parts.lastIndex) return null
    val match = MORPHING_TIP_WORDS.matchEntire(parts[wordIndex]) ?: return null
    val unit = match.groupValues[2]
    val hasWordSuffix = match.groupValues[3].isNotEmpty()
    if (unit.isEmpty() && !hasWordSuffix) return null
    val multiplier = when (unit) {
        "万" -> BigDecimal("10000")
        "亿" -> BigDecimal("100000000")
        else -> BigDecimal.ONE
    }
    val words = runCatching {
        match.groupValues[1].toBigDecimal()
            .multiply(multiplier)
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact()
    }.getOrNull()?.takeIf { it > 0L } ?: return null
    val category = parts[wordIndex - 1]
        .takeIf { it.toDoubleOrNull() == null }
        ?: return null
    return MorphingTipMetadata(category = category, words = words)
}

object BookInfoExtractor {
    private data class MethodKey(val name: String, val parameterCount: Int)

    private class AccessPlan(
        val methods: Map<MethodKey, Method>,
    ) {
        val fields = HashMap<String, Field?>()
    }

    private val accessorCacheLock = Any()
    private val accessorPlans = HashMap<Class<*>, AccessPlan>()

    private val titleMethods = listOf("getBookName", "getComicName", "getItemName", "getName", "getTitle")
    private val titleFields = listOf("BookName", "bookName", "mBookName", "ComicName", "comicName", "itemName", "Title", "title")
    // 注意：MorphingItem 无作者字段；其 ItemSubName 是分类/标签（如“衍生同人”“娱乐明星”），
    // 不是作者，故不放进作者候选（否则作者槽被标签污染，且真作者仍拿不到）。作者只认真正的作者 getter。
    private val authorMethods = listOf("getAuthorName", "getAuthor")
    private val authorFields = listOf("AuthorName", "authorName", "mAuthor", "Author", "author")
    private val categoryMethods = listOf("getCategoryName", "getCategory")
    private val categoryFields = listOf("CategoryName", "categoryName", "mCategoryName", "Category", "category")
    private val subCategoryMethods = listOf("getSubCategoryName", "getSubCategory")
    private val subCategoryFields = listOf("SubCategoryName", "subCategoryName", "SubCategory", "subCategory")
    private val tagMethods = listOf("getTagName", "getTags", "getTagList", "getBookTags", "getExtraTag", "getItemSubName")
    private val tagFields = listOf("TagName", "tagName", "Tags", "tags", "TagList", "tagList", "BookTags", "bookTags", "ExtraTag", "extraTag", "ItemSubName", "itemSubName")
    // getItemDesc is a synopsis/description on MorphingItem, not a category or type. Never
    // extract it into BookInfo, otherwise statistics can accidentally retain book descriptions.
    private val typeMethods = listOf("getBookType", "getType")
    private val typeFields = listOf("BookType", "bookType", "type", "Type")
    private val idMethods = listOf("getBookId", "getQDBookId", "getCmId")
    private val idFields = listOf("BookId", "bookId", "QDBookId", "mQDBookId", "CmId")
    private val wordsMethods = listOf("getWordsCount", "getTotalWords", "getWordsCnt", "getWords")
    private val wordsFields = listOf("WordsCount", "wordsCount", "totalWords", "mWordsCount", "WordsCnt")
    private val nestedBookMethods = listOf("getBookStoreItem")
    private val nestedBookFields = listOf("BookStoreItem")

    init {
        FeatureResources.register("BookInfoExtractor.accessors") { clearAccessorCache() }
    }

    fun extract(source: Any?, spec: BookInfoExtractionSpec = BookInfoExtractionSpec.ALL): BookInfo? {
        source ?: return null
        extractFromBookDetail(source, spec)?.let { return it }
        val morphingTip = if (
            (spec.category || spec.words) && source.javaClass.name == HostSymbols.Classes.MORPHING_ITEM
        ) {
            parseMorphingTip(invokeNoArg(source, "getTip")?.toString())
        } else {
            null
        }
        val title = if (spec.title) firstString(source, titleMethods, titleFields) else null
        val author = if (spec.author) firstString(source, authorMethods, authorFields) else null
        val category = if (spec.category) {
            firstString(source, categoryMethods, categoryFields) ?: morphingTip?.category
        } else {
            null
        }
        val subCategory = if (spec.category) firstString(source, subCategoryMethods, subCategoryFields) else null
        val tags = if (spec.tags) firstTagString(source) else null
        val typeValue = if (spec.type) firstAny(source, typeMethods, typeFields) else null
        val id = if (spec.id) firstLong(source, idMethods, idFields) else null
        val words = if (spec.words) firstLong(source, wordsMethods, wordsFields) ?: morphingTip?.words else null
        if (listOf(title, author, category, subCategory, tags, typeValue?.toString()).all { it.isNullOrBlank() } && id == null && words == null) {
            val nested = firstAny(source, nestedBookMethods, nestedBookFields)
            return nested?.takeUnless { it === source }?.let { extract(it, spec) }
        }
        return BookInfo(
            id = id,
            title = title,
            author = author,
            category = category,
            subCategory = subCategory,
            tags = tags,
            type = typeValue?.toString(),
            typeCode = (typeValue as? Number)?.toInt(),
            words = words,
            sourceClass = source.javaClass.name,
        )
    }

    private fun extractFromBookDetail(source: Any, spec: BookInfoExtractionSpec): BookInfo? {
        if (!source.javaClass.name.endsWith(".BookDetail")) return null
        val base = invokeNoArg(source, "getBaseBookInfo")
        val authorInfo = invokeNoArg(source, "getAuthorInfo")
        val typeValue = if (spec.type) firstAny(base, typeMethods, typeFields) else null
        return BookInfo(
            id = if (spec.id) firstLong(base, idMethods, idFields) else null,
            title = if (spec.title) firstString(base, titleMethods, titleFields) else null,
            author = if (spec.author) firstString(authorInfo, authorMethods, authorFields) else null,
            category = if (spec.category) firstString(base, categoryMethods, categoryFields) else null,
            subCategory = if (spec.category) firstString(base, subCategoryMethods, subCategoryFields) else null,
            tags = if (spec.tags) firstTagString(base) else null,
            type = typeValue?.toString(),
            typeCode = (typeValue as? Number)?.toInt(),
            words = if (spec.words) firstLong(base, wordsMethods, wordsFields) else null,
            sourceClass = source.javaClass.name,
        )
    }

    fun invokeNoArg(source: Any?, name: String): Any? {
        source ?: return null
        return FeatureHooks.invokeOrNull(method(source, name, 0), source)
    }

    fun fieldValue(source: Any?, name: String): Any? {
        source ?: return null
        val field = field(source, name) ?: return null
        return FeatureHooks.read(field, source)
    }

    fun method(source: Any?, name: String, parameterCount: Int): Method? {
        source ?: return null
        return accessPlan(source.javaClass).methods[MethodKey(name, parameterCount)]
    }

    fun field(source: Any?, name: String): Field? {
        source ?: return null
        val plan = accessPlan(source.javaClass)
        synchronized(plan) {
            if (plan.fields.containsKey(name)) {
                return plan.fields[name]
            }
            var clazz: Class<*>? = source.javaClass
            while (clazz != null) {
                FeatureHooks.findFieldOrNull(clazz, name)?.let { resolved ->
                    plan.fields[name] = resolved
                    return resolved
                }
                clazz = clazz.superclass
            }
            plan.fields[name] = null
            return null
        }
    }

    fun hasAnyAccessor(
        source: Any?,
        methodNames: Collection<String>,
        fieldNames: Collection<String>,
    ): Boolean {
        source ?: return false
        val plan = accessPlan(source.javaClass)
        if (methodNames.any { plan.methods.containsKey(MethodKey(it, 0)) }) return true
        return fieldNames.any { field(source, it) != null }
    }

    internal fun clearAccessorCache() {
        synchronized(accessorCacheLock) {
            accessorPlans.clear()
        }
    }

    private fun accessPlan(clazz: Class<*>): AccessPlan {
        synchronized(accessorCacheLock) {
            accessorPlans[clazz]?.let { return it }
            val methods = LinkedHashMap<MethodKey, Method>()
            FeatureHooks.publicMethods(clazz).forEach { method ->
                methods.putIfAbsent(MethodKey(method.name, method.parameterTypes.size), method)
            }
            return AccessPlan(methods).also { accessorPlans[clazz] = it }
        }
    }

    private fun firstString(source: Any?, methods: List<String>, fields: List<String>): String? {
        return firstAny(source, methods, fields)?.toString()?.takeIf { it.isNotBlank() }
    }

    private fun firstLong(source: Any?, methods: List<String>, fields: List<String>): Long? {
        return when (val value = firstAny(source, methods, fields)) {
            is Number -> value.toLong()
            is String -> value.trim().toLongOrNull()
            else -> null
        }
    }

    private fun firstTagString(source: Any?): String? {
        return tagMethods.asSequence()
            .mapNotNull { methodName -> invokeNoArg(source, methodName) }
            .plus(tagFields.asSequence().mapNotNull { fieldName -> fieldValue(source, fieldName) })
            .mapNotNull(::tagValueToString)
            .firstOrNull()
    }

    private fun tagValueToString(value: Any?): String? {
        val values = when (value) {
            null -> emptyList()
            is Iterable<*> -> value.mapNotNull(::singleTagValue)
            is Array<*> -> value.mapNotNull(::singleTagValue)
            else -> listOfNotNull(singleTagValue(value))
        }
        return values.map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .joinToString("、")
            .takeIf(String::isNotEmpty)
    }

    private fun singleTagValue(value: Any?): String? {
        value ?: return null
        if (value is Number) return null
        if (value is CharSequence) return normalizedTagText(value.toString())
        return normalizedTagText(invokeNoArg(value, "getName")?.toString())
            ?: normalizedTagText(invokeNoArg(value, "getTagName")?.toString())
            ?: normalizedTagText((value as? Enum<*>)?.name)
    }

    private fun normalizedTagText(value: String?): String? {
        val normalized = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        // MorphingItem.ItemSubName is polymorphic. Some cards put a numeric presentation/type
        // code (for example "2" or "9") there when their real Tags list is empty. Such values
        // are host enums, not user-facing book tags, and must never leak into statistics.
        return normalized.takeUnless { it.toDoubleOrNull() != null }
    }

    private fun firstAny(source: Any?, methods: List<String>, fields: List<String>): Any? {
        methods.forEach { methodName ->
            invokeNoArg(source, methodName)?.let { return it }
        }
        fields.forEach { fieldName ->
            fieldValue(source, fieldName)?.let { return it }
        }
        return null
    }
}
