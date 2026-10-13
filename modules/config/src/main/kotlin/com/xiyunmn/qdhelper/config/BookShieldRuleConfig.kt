package com.xiyunmn.qdhelper.config

import java.util.Locale

data class BookShieldRuleConfig(
    val authors: List<String> = emptyList(),
    val titleKeywords: List<String> = emptyList(),
    val fullTitles: List<String> = emptyList(),
    val types: List<String> = emptyList(),
    val bookIds: List<Long> = emptyList(),
    val wordsAtMost: Long? = null,
    val wordsAtLeast: Long? = null,
) {
    val ruleCount: Int
        get() = authors.size + titleKeywords.size + fullTitles.size + types.size + bookIds.size +
            listOfNotNull(wordsAtMost, wordsAtLeast).size

    fun normalized(): BookShieldRuleConfig = copy(
        authors = normalizeTextRules(authors),
        titleKeywords = normalizeTextRules(titleKeywords),
        fullTitles = normalizeTextRules(fullTitles),
        types = normalizeTextRules(types),
        bookIds = bookIds.distinct(),
    ).also { value ->
        require(value.bookIds.all { it > 0L }) { "book_ids 只接受正整数" }
        require(value.wordsAtMost == null || value.wordsAtMost > 0L) { "word_count.at_most 必须是正整数或 null" }
        require(value.wordsAtLeast == null || value.wordsAtLeast > 0L) { "word_count.at_least 必须是正整数或 null" }
        require(
            value.wordsAtMost == null || value.wordsAtLeast == null || value.wordsAtMost < value.wordsAtLeast,
        ) { "word_count.at_most 必须小于 word_count.at_least" }
    }

    companion object {
        val EMPTY = BookShieldRuleConfig()

        fun normalizeIdentity(value: String?): String = value
            ?.trim()
            ?.replace(Regex("\\s+"), " ")
            ?.lowercase(Locale.ROOT)
            .orEmpty()

        fun normalizeDisplay(value: String?): String = value
            ?.trim()
            ?.replace(Regex("\\s+"), " ")
            .orEmpty()

        /**
         * 文本规则的输入分隔：只按换行切分，一行一条。
         * 书名、作者名里的逗号、分号、空格都是内容的一部分，不能当分隔符。
         */
        fun splitLines(value: String): List<String> = value
            .split('\n')
            .map(::normalizeDisplay)
            .filter(String::isNotEmpty)

        /** 宽松分隔：仅用于书号输入与旧版 SharedPreferences 迁移，旧数据本就是这么存的。 */
        fun splitQuickInput(value: String): List<String> = value
            .split(Regex("[\\s,，;；]+"))
            .map(::normalizeDisplay)
            .filter(String::isNotEmpty)

        private fun normalizeTextRules(values: List<String>): List<String> {
            val identities = linkedSetOf<String>()
            return buildList {
                values.forEach { raw ->
                    val display = normalizeDisplay(raw)
                    val identity = normalizeIdentity(display)
                    if (identity.isNotEmpty() && identities.add(identity)) add(display)
                }
            }
        }
    }
}

data class BookShieldRuntimeSnapshot(
    val revision: Long,
    val config: BookShieldRuleConfig,
)

enum class BookShieldRuleField {
    AUTHORS,
    TITLE_KEYWORDS,
    FULL_TITLES,
    TYPES,
    BOOK_IDS,
}

data class BookShieldYamlError(
    val message: String,
    val path: String? = null,
    val line: Int? = null,
    val column: Int? = null,
) {
    fun displayMessage(): String = buildString {
        path?.let { append(it).append("：") }
        append(message)
        if (line != null && column != null) append("（第 ").append(line).append(" 行，第 ").append(column).append(" 列）")
    }
}

sealed interface BookShieldYamlResult {
    data class Success(
        val config: BookShieldRuleConfig,
        val text: String,
    ) : BookShieldYamlResult

    data class Failure(val error: BookShieldYamlError) : BookShieldYamlResult
}
