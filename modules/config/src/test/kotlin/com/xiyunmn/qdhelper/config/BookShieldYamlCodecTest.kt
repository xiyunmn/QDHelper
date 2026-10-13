package com.xiyunmn.qdhelper.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BookShieldYamlCodecTest {
    @Test
    fun `template round trips and keeps comments`() {
        val result = BookShieldYamlCodec.decode(BookShieldYamlCodec.template()) as BookShieldYamlResult.Success

        assertEquals(BookShieldRuleConfig.EMPTY, result.config)
        assertTrue(result.text.contains("# 精确匹配作者名"))
        assertTrue(result.text.contains("word_count:"))
    }

    @Test
    fun `text rules preserve first spelling and deduplicate by normalized identity`() {
        val result = success(
            """
            version: 1
            authors: [" Author  Name ", author name, 作者甲]
            title_keywords: []
            full_titles: []
            types: [1, "1"]
            book_ids: [12, "12", 13]
            word_count: { at_most: 100, at_least: 200 }
            """.trimIndent(),
        )

        assertEquals(listOf("Author Name", "作者甲"), result.config.authors)
        assertEquals(listOf("1"), result.config.types)
        assertEquals(listOf(12L, 13L), result.config.bookIds)
        assertEquals(100L, result.config.wordsAtMost)
        assertEquals(200L, result.config.wordsAtLeast)
    }

    @Test
    fun `block and flow lists accept plain and quoted scalars`() {
        val result = success(
            """
            version: 1
            authors:
              - 作者甲
              - "Author Name"
            title_keywords: [玄幻, '科 幻']
            full_titles: []
            types: []
            book_ids: ["12", 13]
            word_count: { at_most: "100", at_least: 200 }
            """.trimIndent(),
        )

        assertEquals(listOf("作者甲", "Author Name"), result.config.authors)
        assertEquals(listOf("玄幻", "科 幻"), result.config.titleKeywords)
        assertEquals(listOf(12L, 13L), result.config.bookIds)
        assertEquals(100L, result.config.wordsAtMost)
    }

    @Test
    fun `chinese commas are content and never split a list item`() {
        val result = success(
            """
            version: 1
            authors: [作者甲，作者乙]
            title_keywords: [华娱，斗罗]
            full_titles: ['书名，副标题']
            types:
              - 玄幻，科幻
            book_ids: []
            word_count: { at_most: null, at_least: null }
            """.trimIndent(),
        )

        assertEquals(listOf("作者甲，作者乙"), result.config.authors)
        assertEquals(listOf("华娱，斗罗"), result.config.titleKeywords)
        assertEquals(listOf("书名，副标题"), result.config.fullTitles)
        assertEquals(listOf("玄幻，科幻"), result.config.types)
        assertEquals(result.config, success(result.text).config)
    }

    @Test
    fun `real book titles with punctuation survive verbatim`() {
        val titles = listOf(
            "我正经学生，每天只吃九种魔药",
            "我，美国法外狂徒，打钱",
            "大明：让你死谏，你怎么真死啊？",
            "人在北宋，开局打断西夏脊梁！",
            "1985：开局大雪封门",
        )
        val source = buildString {
            appendLine("version: 1")
            appendLine("full_titles:")
            titles.forEach { appendLine("  - $it") }
        }

        val result = success(source)

        assertEquals(titles, result.config.fullTitles)
        assertEquals(titles, success(result.text).config.fullTitles)
    }

    @Test
    fun `values added to the empty template stay one rule across a round trip`() {
        val title = "我，美国法外狂徒，打钱"
        val written = BookShieldYamlCodec.rewrite(
            BookShieldYamlCodec.template(),
            BookShieldRuleConfig(fullTitles = listOf(title)),
        ) as BookShieldYamlResult.Success

        assertTrue("expected block list, got:\n${written.text}", written.text.contains("- $title"))
        assertEquals(listOf(title), success(written.text).config.fullTitles)
    }

    @Test
    fun `empty lists stay inline in the template`() {
        assertTrue(BookShieldYamlCodec.template().contains("authors: []"))
    }

    @Test
    fun `rewrite preserves comments while changing semantic values`() {
        val source = """
            # root
            version: 1
            # author list
            authors:
              - old # item
            title_keywords: []
            full_titles: []
            types: []
            book_ids: []
            word_count:
              # low
              at_most: null
              at_least: null
        """.trimIndent()
        val result = BookShieldYamlCodec.rewrite(
            source,
            BookShieldRuleConfig(authors = listOf("old", "new"), wordsAtMost = 100L),
        ) as BookShieldYamlResult.Success

        assertTrue(result.text.contains("# root"))
        assertTrue(result.text.contains("# author list"))
        assertTrue(result.text.contains("old # item"))
        assertTrue(result.text.contains("# low"))
        assertEquals(listOf("old", "new"), result.config.authors)
    }

    @Test
    fun `unsafe and ambiguous yaml is rejected`() {
        listOf(
            "version: 1\nauthors: &a [x]\ntitle_keywords: *a",
            "version: 1\nauthors: []\nauthors: []",
            "version: 1\nauthors: [true]",
            "version: 1\nauthors: [!!str tagged]",
            "version: 1\nauthors: [!custom tagged]",
            "version: 1\nunknown: []",
            "version: 1\n---\nversion: 1",
            "version: 1\nword_count: { at_most: 200, at_least: 100 }",
            "version: 1\nword_count: { <<: { at_most: 1 }, at_least: 2 }",
            "version: 1\n? [authors]\n: []",
        ).forEach { yaml ->
            assertTrue("expected failure for $yaml", BookShieldYamlCodec.decode(yaml) is BookShieldYamlResult.Failure)
        }
    }

    @Test
    fun `yaml byte limit is enforced before parsing`() {
        val oversized = BookShieldYamlCodec.template() + "#" + "a".repeat(BookShieldYamlCodec.MAX_BYTES)

        val result = BookShieldYamlCodec.decode(oversized) as BookShieldYamlResult.Failure

        assertTrue(result.error.message.contains("1 MiB"))
    }

    @Test
    fun `validation error reports field location`() {
        val result = BookShieldYamlCodec.decode("version: 1\nbook_ids: [bad]") as BookShieldYamlResult.Failure

        assertEquals("book_ids[0]", result.error.path)
        assertEquals(2, result.error.line)
        assertTrue(result.error.column != null)
    }

    @Test
    fun `programmatic ids must also be positive`() {
        assertThrows(IllegalArgumentException::class.java) {
            BookShieldRuleConfig(bookIds = listOf(1L, 0L)).normalized()
        }
    }

    private fun success(text: String): BookShieldYamlResult.Success =
        BookShieldYamlCodec.decode(text) as BookShieldYamlResult.Success
}
