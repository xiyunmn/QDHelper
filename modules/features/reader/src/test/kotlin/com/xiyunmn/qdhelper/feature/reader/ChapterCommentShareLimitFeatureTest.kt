package com.xiyunmn.qdhelper.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterCommentShareLimitFeatureTest {
    @Test
    fun recognizesDailyShareLimitMessages() {
        assertTrue(isDailyShareLimitMessage("每天只能分享3次"))
        assertTrue(isDailyShareLimitMessage("今日分享次数已达上限"))
        assertTrue(isDailyShareLimitMessage("每日分享次数已用完"))
        assertTrue(isDailyShareLimitMessage("同一用户一天内最多分享3次"))
        assertTrue(isDailyShareLimitMessage("分享次数已达上限，请明日再试"))
        assertTrue(isDailyShareLimitMessage("今日已达上限"))
    }

    @Test
    fun preservesOtherSharePolicyFailures() {
        assertFalse(isDailyShareLimitMessage("该章节暂不支持分享"))
        assertFalse(isDailyShareLimitMessage("内容审核中，请稍后再试"))
        assertFalse(isDailyShareLimitMessage("本章为付费章节"))
        assertFalse(isDailyShareLimitMessage("已达上限"))
        assertFalse(isDailyShareLimitMessage(null))
    }

    @Test
    fun repairsOnlyMissingWordCountFromRejectedPolicy() {
        assertEquals(Int.MAX_VALUE, normalizedDailyShareWordCount(0))
        assertEquals(Int.MAX_VALUE, normalizedDailyShareWordCount(-1))
        assertEquals(120, normalizedDailyShareWordCount(120))
    }
}
