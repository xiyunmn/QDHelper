package com.xiyunmn.qdhelper.feature.bookshield.ui

import android.content.Context
import com.xiyunmn.qdhelper.config.BookShieldRuleRepository
import com.xiyunmn.qdhelper.feature.bookshield.policy.WordCountBlockRule

internal object BookShieldWordRangeText {
    fun summary(context: Context): String {
        val rule = currentRule(context)
        rule.validationError?.let { return "配置无效：$it" }
        return when {
            rule.atMost != null && rule.atLeast != null ->
                "屏蔽 ≤${formatWords(rule.atMost)} 或 ≥${formatWords(rule.atLeast)}"
            rule.atMost != null -> "屏蔽 ≤${formatWords(rule.atMost)}"
            rule.atLeast != null -> "屏蔽 ≥${formatWords(rule.atLeast)}"
            else -> "未启用字数屏蔽"
        }
    }

    private fun currentRule(context: Context): WordCountBlockRule =
        BookShieldRuleRepository.snapshot(context).let { WordCountBlockRule(it.wordsAtMost, it.wordsAtLeast) }

    private fun formatWords(value: Long): String {
        return if (value >= 10_000L && value % 10_000L == 0L) {
            "${value / 10_000L} 万字"
        } else {
            "$value 字"
        }
    }

}
