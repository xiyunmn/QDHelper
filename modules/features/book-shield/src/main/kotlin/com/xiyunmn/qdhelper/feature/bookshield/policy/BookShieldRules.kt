package com.xiyunmn.qdhelper.feature.bookshield.policy

import com.xiyunmn.qdhelper.feature.bookshield.model.BookInfo
import com.xiyunmn.qdhelper.feature.bookshield.model.BookInfoExtractionSpec
import android.content.Context
import com.xiyunmn.qdhelper.config.BookShieldRuleConfig
import com.xiyunmn.qdhelper.config.BookShieldRuleRepository
import com.xiyunmn.qdhelper.config.SettingsRuntime
import java.util.Locale

/** Which rule caused a book to be shielded, for the statistics view. */
enum class ShieldReasonKind(val label: String) {
    BOOK_ID("书号"),
    AUTHOR("作者"),
    FULL_TITLE("完整书名"),
    KEYWORD("书名关键词"),
    TYPE("分类/类型"),
    WORDS_TOO_SHORT("字数过少"),
    WORDS_TOO_LONG("字数过多"),
}

/** The matched rule plus the concrete value that matched (author name, keyword, category, ...). */
data class ShieldReason(val kind: ShieldReasonKind, val detail: String)

data class BookShieldRules(
    val authors: Set<String>,
    val titleKeywords: Set<String>,
    val fullTitles: Set<String>,
    val types: Set<String>,
    val typeContains: Boolean,
    val wordCountRule: WordCountBlockRule,
    val allowEmptyResult: Boolean,
    val bookIds: Set<Long>,
) {
    val hasRules: Boolean
        get() = authors.isNotEmpty() ||
            titleKeywords.isNotEmpty() ||
            fullTitles.isNotEmpty() ||
            types.isNotEmpty() ||
            bookIds.isNotEmpty() ||
            wordCountRule.isEnabled

    val extractionSpec: BookInfoExtractionSpec
        get() = BookInfoExtractionSpec(
            // Title also acts as the strongest cheap signal that an extracted object is a leaf
            // book item rather than a container with an unrelated type/id property.
            title = true,
            author = authors.isNotEmpty(),
            category = types.isNotEmpty(),
            tags = types.isNotEmpty(),
            type = types.isNotEmpty(),
            id = bookIds.isNotEmpty(),
            words = wordCountRule.isEnabled,
        )

    fun matches(info: BookInfo): Boolean = matchReason(info) != null

    /**
     * Returns the first rule that matches [info], or null when nothing matches. Rule order
     * mirrors [matches]; the returned detail is the concrete value that hit (book id, author
     * name, keyword, original category text, or a word-count description) for the stats view.
     */
    fun matchReason(info: BookInfo): ShieldReason? {
        if (info.id != null && bookIds.contains(info.id)) {
            return ShieldReason(ShieldReasonKind.BOOK_ID, info.id.toString())
        }
        val title = normalize(info.title)
        val author = normalize(info.author)
        val typeOriginals = listOfNotNull(
            info.category,
            info.subCategory,
            info.tags,
            info.type,
            info.typeCode?.toString(),
        ).map { it.trim() }.filter { it.isNotEmpty() }

        if (author.isNotEmpty() && authors.contains(author)) {
            return ShieldReason(ShieldReasonKind.AUTHOR, info.author?.trim().orEmpty().ifEmpty { author })
        }
        if (title.isNotEmpty() && fullTitles.contains(title)) {
            return ShieldReason(ShieldReasonKind.FULL_TITLE, info.title?.trim().orEmpty().ifEmpty { title })
        }
        if (title.isNotEmpty()) {
            val keyword = titleKeywords.firstOrNull { title.contains(it) }
            if (keyword != null) return ShieldReason(ShieldReasonKind.KEYWORD, keyword)
        }
        if (types.isNotEmpty()) {
            val matched = typeOriginals.firstOrNull { original ->
                val value = normalize(original)
                value.isNotEmpty() && if (typeContains) types.any { value.contains(it) } else types.contains(value)
            }
            if (matched != null) return ShieldReason(ShieldReasonKind.TYPE, matched)
        }
        when (val wordMatch = wordCountRule.match(info.words)) {
            is WordCountMatch.TooShort -> return ShieldReason(
                ShieldReasonKind.WORDS_TOO_SHORT,
                "≤${wordMatch.threshold} 字（实=${wordMatch.actual}）",
            )
            is WordCountMatch.TooLong -> return ShieldReason(
                ShieldReasonKind.WORDS_TOO_LONG,
                "≥${wordMatch.threshold} 字（实=${wordMatch.actual}）",
            )
            null -> Unit
        }
        return null
    }

    companion object {
        private data class RawRules(
            val revision: Long,
            val config: BookShieldRuleConfig,
            val typeContains: Boolean,
            val allowEmptyResult: Boolean,
        )

        private data class CachedRules(val raw: RawRules, val parsed: BookShieldRules)

        private val cacheLock = Any()

        @Volatile
        private var cachedRules: CachedRules? = null

        fun from(context: Context?): BookShieldRules {
            val snapshot = BookShieldRuleRepository.runtimeSnapshot(context)
            val raw = RawRules(
                revision = snapshot.revision,
                config = snapshot.config,
                typeContains = SettingsRuntime.isBookShieldTypeContainsEnabled(context),
                allowEmptyResult = SettingsRuntime.isBookShieldEmptyResultAllowed(context),
            )
            cachedRules?.takeIf { it.raw == raw }?.let { return it.parsed }
            return synchronized(cacheLock) {
                cachedRules?.takeIf { it.raw == raw }?.parsed ?: BookShieldRules(
                    authors = raw.config.authors.map(::normalize).toSet(),
                    titleKeywords = raw.config.titleKeywords.map(::normalize).toSet(),
                    fullTitles = raw.config.fullTitles.map(::normalize).toSet(),
                    types = raw.config.types.map(::normalize).toSet(),
                    typeContains = raw.typeContains,
                    wordCountRule = WordCountBlockRule(raw.config.wordsAtMost, raw.config.wordsAtLeast),
                    allowEmptyResult = raw.allowEmptyResult,
                    bookIds = raw.config.bookIds.toSet(),
                ).also { parsed -> cachedRules = CachedRules(raw, parsed) }
            }
        }

        fun normalize(value: String?): String {
            return value
                ?.trim()
                ?.replace(Regex("\\s+"), " ")
                ?.lowercase(Locale.ROOT)
                .orEmpty()
        }
    }
}
