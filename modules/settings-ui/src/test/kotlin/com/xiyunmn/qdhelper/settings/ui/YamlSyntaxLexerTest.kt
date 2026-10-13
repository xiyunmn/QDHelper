package com.xiyunmn.qdhelper.settings.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YamlSyntaxLexerTest {
    @Test
    fun `highlights yaml keys scalars and comments`() {
        val text = "authors: [\"a#b\", plain#value] # comment"
        val tokens = yamlSyntaxTokens(text)

        assertEquals(listOf("authors"), tokens.textOf(text, YamlSyntaxKind.KEY))
        assertEquals(listOf("\"a#b\"", "plain#value"), tokens.textOf(text, YamlSyntaxKind.STRING))
        assertEquals(listOf("# comment"), tokens.textOf(text, YamlSyntaxKind.COMMENT))
    }

    @Test
    fun `highlights list markers numbers and null values`() {
        val text = """
            version: 1
            book_ids:
              - 123
            word_count: { at_most: null, at_least: 200 }
        """.trimIndent()
        val tokens = yamlSyntaxTokens(text)

        assertEquals(
            listOf("version", "book_ids", "word_count", "at_most", "at_least"),
            tokens.textOf(text, YamlSyntaxKind.KEY),
        )
        assertEquals(listOf("1", "123", "200"), tokens.textOf(text, YamlSyntaxKind.NUMBER))
        assertEquals(listOf("null"), tokens.textOf(text, YamlSyntaxKind.KEYWORD))
        assertTrue(tokens.textOf(text, YamlSyntaxKind.PUNCTUATION).contains("-"))
    }

    @Test
    fun `highlights unquoted yaml strings`() {
        val text = "authors: [张三, science_fiction]"

        assertEquals(
            listOf("张三", "science_fiction"),
            yamlSyntaxTokens(text).textOf(text, YamlSyntaxKind.STRING),
        )
    }

    @Test
    fun `quoted colons and hashes do not start mappings or comments`() {
        val text = "  - \"https://example.test/#fragment\" # actual"
        val tokens = yamlSyntaxTokens(text)

        assertTrue(tokens.textOf(text, YamlSyntaxKind.KEY).isEmpty())
        assertEquals(listOf("\"https://example.test/#fragment\""), tokens.textOf(text, YamlSyntaxKind.STRING))
        assertEquals(listOf("# actual"), tokens.textOf(text, YamlSyntaxKind.COMMENT))
    }

    @Test
    fun `tokens are ordered and non overlapping`() {
        val tokens = yamlSyntaxTokens("book_ids: [1, 2] # ids")

        tokens.zipWithNext().forEach { (left, right) ->
            assertTrue(left.end <= right.start)
        }
    }

    private fun List<YamlSyntaxToken>.textOf(text: String, kind: YamlSyntaxKind): List<String> =
        filter { it.kind == kind }.map { text.substring(it.start, it.end) }
}
