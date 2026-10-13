package com.xiyunmn.qdhelper.feature.bookshield.policy

data class WordCountBlockRule(
    val atMost: Long?,
    val atLeast: Long?,
    val validationError: String? = null,
) {
    val isEnabled: Boolean
        get() = validationError == null && (atMost != null || atLeast != null)

    fun match(words: Long?): WordCountMatch? {
        if (!isEnabled || words == null || words <= 0L) return null
        return when {
            atMost != null && words <= atMost -> WordCountMatch.TooShort(words, atMost)
            atLeast != null && words >= atLeast -> WordCountMatch.TooLong(words, atLeast)
            else -> null
        }
    }

    companion object {
        fun parse(atMostRaw: String, atLeastRaw: String): WordCountBlockRule {
            val atMost = parseBound(atMostRaw, "屏蔽字数不超过")
            val atLeast = parseBound(atLeastRaw, "屏蔽字数不少于")
            val error = atMost.error ?: atLeast.error ?: when {
                atMost.value != null && atLeast.value != null && atMost.value >= atLeast.value ->
                    "下侧阈值必须小于上侧阈值"
                else -> null
            }
            return WordCountBlockRule(atMost.value, atLeast.value, error)
        }

        private fun parseBound(raw: String, label: String): ParsedBound {
            val value = raw.trim()
            if (value.isEmpty()) return ParsedBound(null, null)
            val parsed = value.toLongOrNull()
                ?: return ParsedBound(null, "${label}必须是正整数")
            if (parsed <= 0L) return ParsedBound(null, "${label}必须大于 0")
            return ParsedBound(parsed, null)
        }

        private data class ParsedBound(val value: Long?, val error: String?)
    }
}

sealed interface WordCountMatch {
    val actual: Long
    val threshold: Long

    data class TooShort(
        override val actual: Long,
        override val threshold: Long,
    ) : WordCountMatch

    data class TooLong(
        override val actual: Long,
        override val threshold: Long,
    ) : WordCountMatch
}
