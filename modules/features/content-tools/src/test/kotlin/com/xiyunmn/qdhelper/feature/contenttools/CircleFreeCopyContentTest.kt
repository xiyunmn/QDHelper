package com.xiyunmn.qdhelper.feature.contenttools

import org.junit.Assert.assertEquals
import org.junit.Test

class CircleFreeCopyContentTest {
    @Test
    fun `combines title and body without using truncated view text`() {
        assertEquals("帖子标题\n\n完整正文", composeCircleCopyContent(" 帖子标题 ", "\n完整正文\n"))
    }

    @Test
    fun `supports comments and avoids duplicate title`() {
        assertEquals("评论正文", composeCircleCopyContent(null, "评论正文"))
        assertEquals("相同内容", composeCircleCopyContent("相同内容", "相同内容"))
    }

    @Test
    fun `returns empty content when model has no text`() {
        assertEquals("", composeCircleCopyContent("  ", null))
    }

    @Test
    fun `filters standard rich text fields from list body`() {
        val body = """[{"Type":1,"Text":"第一段"},{"Type":1,"Text":"第二段"}]"""

        assertEquals("第一段第二段", CircleRichTextFilter.toPlainText(body))
        assertEquals("帖子标题\n\n第一段第二段", composeCircleCopyContent("帖子标题", body))
    }

    @Test
    fun `filters lowercase rich text variants without leaking parameter names`() {
        val body = """[{"type":"txt","txt":"正文"},{"type":"text","content":"内容"}]"""

        assertEquals("正文内容", CircleRichTextFilter.toPlainText(body))
    }

    @Test
    fun `renders mention nickname and ignores its structural fields`() {
        val body = """[{"Type":1,"Text":"你好 "},{"Type":10,"Text":"{\"UserId\":1,\"NickName\":\"书友\"}"}]"""

        assertEquals("你好 @书友", CircleRichTextFilter.toPlainText(body))
    }

    @Test
    fun `recovers text from malformed structured content without returning raw object`() {
        val body = """[{"type":"txt","txt":"正文"},BROKEN]"""

        assertEquals("正文", CircleRichTextFilter.toPlainText(body))
    }

    @Test
    fun `joins detail body sections under one title`() {
        val body = listOf("第一段", "第二段").joinToString("\n\n")

        assertEquals("详情标题\n\n第一段\n\n第二段", composeCircleCopyContent("详情标题", body))
    }
}
