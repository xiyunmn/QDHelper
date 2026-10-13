package com.xiyunmn.qdhelper.settings.ui

internal enum class YamlSyntaxKind {
    COMMENT,
    KEY,
    STRING,
    NUMBER,
    KEYWORD,
    PUNCTUATION,
}

internal data class YamlSyntaxToken(
    val start: Int,
    val end: Int,
    val kind: YamlSyntaxKind,
)

internal fun yamlSyntaxTokens(text: String): List<YamlSyntaxToken> = buildList {
    var lineStart = 0
    while (lineStart <= text.length) {
        val lineEnd = text.indexOf('\n', lineStart).takeIf { it >= 0 } ?: text.length
        tokenizeYamlLine(text, lineStart, lineEnd, this)
        if (lineEnd == text.length) break
        lineStart = lineEnd + 1
    }
}

private fun tokenizeYamlLine(
    text: String,
    lineStart: Int,
    lineEnd: Int,
    output: MutableList<YamlSyntaxToken>,
) {
    var contentStart = lineStart
    while (contentStart < lineEnd && text[contentStart].isWhitespace()) contentStart += 1
    if (contentStart >= lineEnd) return

    var listItem = false
    if (text[contentStart] == '-' && contentStart + 1 < lineEnd && text[contentStart + 1].isWhitespace()) {
        output += YamlSyntaxToken(contentStart, contentStart + 1, YamlSyntaxKind.PUNCTUATION)
        listItem = true
        contentStart += 1
        while (contentStart < lineEnd && text[contentStart].isWhitespace()) contentStart += 1
    }

    val commentStart = findYamlComment(text, contentStart, lineEnd)
    val contentEnd = commentStart.takeIf { it >= 0 } ?: lineEnd

    var valueStart = contentStart
    val keyColon = if (listItem) -1 else findYamlKeyColon(text, contentStart, contentEnd)
    if (keyColon >= 0) {
        var keyEnd = keyColon
        while (keyEnd > contentStart && text[keyEnd - 1].isWhitespace()) keyEnd -= 1
        if (keyEnd > contentStart) {
            output += YamlSyntaxToken(contentStart, keyEnd, YamlSyntaxKind.KEY)
        }
        output += YamlSyntaxToken(keyColon, keyColon + 1, YamlSyntaxKind.PUNCTUATION)
        valueStart = keyColon + 1
    }
    tokenizeYamlValue(text, valueStart, contentEnd, output)
    if (commentStart >= 0) {
        output += YamlSyntaxToken(commentStart, lineEnd, YamlSyntaxKind.COMMENT)
    }
}

private fun findYamlComment(text: String, start: Int, end: Int): Int {
    var quote: Char? = null
    var index = start
    while (index < end) {
        val char = text[index]
        when (quote) {
            '\'' -> {
                if (char == '\'' && index + 1 < end && text[index + 1] == '\'') {
                    index += 2
                    continue
                }
                if (char == '\'') quote = null
            }
            '"' -> {
                if (char == '\\' && index + 1 < end) {
                    index += 2
                    continue
                }
                if (char == '"') quote = null
            }
            else -> when {
                char == '\'' || char == '"' -> quote = char
                char == '#' && (index == start || text[index - 1].isWhitespace()) -> return index
            }
        }
        index += 1
    }
    return -1
}

private fun findYamlKeyColon(text: String, start: Int, end: Int): Int {
    var quote: Char? = null
    var index = start
    while (index < end) {
        val char = text[index]
        when (quote) {
            '\'' -> {
                if (char == '\'' && index + 1 < end && text[index + 1] == '\'') {
                    index += 2
                    continue
                }
                if (char == '\'') quote = null
            }
            '"' -> {
                if (char == '\\' && index + 1 < end) {
                    index += 2
                    continue
                }
                if (char == '"') quote = null
            }
            else -> when {
                char == '\'' || char == '"' -> quote = char
                char == ':' && (index + 1 == end || text[index + 1].isWhitespace()) -> return index
            }
        }
        index += 1
    }
    return -1
}

private fun tokenizeYamlValue(
    text: String,
    start: Int,
    end: Int,
    output: MutableList<YamlSyntaxToken>,
) {
    var index = start
    var flowKeyExpected = false
    while (index < end) {
        val char = text[index]
        when {
            char.isWhitespace() -> index += 1
            flowKeyExpected -> {
                val keyColon = findFlowKeyColon(text, index, end)
                if (keyColon >= 0) {
                    var keyEnd = keyColon
                    while (keyEnd > index && text[keyEnd - 1].isWhitespace()) keyEnd -= 1
                    if (keyEnd > index) {
                        output += YamlSyntaxToken(index, keyEnd, YamlSyntaxKind.KEY)
                    }
                    output += YamlSyntaxToken(keyColon, keyColon + 1, YamlSyntaxKind.PUNCTUATION)
                    index = keyColon + 1
                    flowKeyExpected = false
                } else {
                    flowKeyExpected = false
                }
            }
            char == '\'' || char == '"' -> {
                val quotedEnd = findQuotedScalarEnd(text, index, end, char)
                output += YamlSyntaxToken(index, quotedEnd, YamlSyntaxKind.STRING)
                index = quotedEnd
            }
            char.isDigit() || ((char == '+' || char == '-') && index + 1 < end && text[index + 1].isDigit()) -> {
                val numberEnd = findNumberEnd(text, index, end)
                if (isTokenBoundary(text, numberEnd, end)) {
                    output += YamlSyntaxToken(index, numberEnd, YamlSyntaxKind.NUMBER)
                    index = numberEnd
                } else {
                    val scalarEnd = findPlainScalarEnd(text, index, end)
                    output += YamlSyntaxToken(index, scalarEnd, YamlSyntaxKind.STRING)
                    index = scalarEnd
                }
            }
            char.isLetter() -> {
                val scalarEnd = findPlainScalarEnd(text, index, end)
                val kind = if (text.substring(index, scalarEnd).lowercase() in YAML_KEYWORDS) {
                    YamlSyntaxKind.KEYWORD
                } else {
                    YamlSyntaxKind.STRING
                }
                output += YamlSyntaxToken(index, scalarEnd, kind)
                index = scalarEnd
            }
            char == '~' -> {
                output += YamlSyntaxToken(index, index + 1, YamlSyntaxKind.KEYWORD)
                index += 1
            }
            char in YAML_PUNCTUATION -> {
                output += YamlSyntaxToken(index, index + 1, YamlSyntaxKind.PUNCTUATION)
                flowKeyExpected = char == '{' || char == ','
                index += 1
            }
            else -> index += 1
        }
    }
}

private fun findFlowKeyColon(text: String, start: Int, end: Int): Int {
    var quote: Char? = null
    var index = start
    while (index < end) {
        val char = text[index]
        when (quote) {
            '\'' -> {
                if (char == '\'' && index + 1 < end && text[index + 1] == '\'') {
                    index += 2
                    continue
                }
                if (char == '\'') quote = null
            }
            '"' -> {
                if (char == '\\' && index + 1 < end) {
                    index += 2
                    continue
                }
                if (char == '"') quote = null
            }
            else -> when {
                char == '\'' || char == '"' -> quote = char
                char == ':' && (index + 1 == end || text[index + 1].isWhitespace()) -> return index
                char == ',' || char == '}' || char == ']' -> return -1
            }
        }
        index += 1
    }
    return -1
}

private fun findQuotedScalarEnd(text: String, start: Int, end: Int, quote: Char): Int {
    var index = start + 1
    while (index < end) {
        val char = text[index]
        if (quote == '"' && char == '\\' && index + 1 < end) {
            index += 2
            continue
        }
        if (quote == '\'' && char == '\'' && index + 1 < end && text[index + 1] == '\'') {
            index += 2
            continue
        }
        index += 1
        if (char == quote) break
    }
    return index
}

private fun findNumberEnd(text: String, start: Int, end: Int): Int {
    var index = start
    if (text[index] == '+' || text[index] == '-') index += 1
    while (index < end && text[index].isDigit()) index += 1
    if (index + 1 < end && text[index] == '.' && text[index + 1].isDigit()) {
        index += 1
        while (index < end && text[index].isDigit()) index += 1
    }
    if (index < end && (text[index] == 'e' || text[index] == 'E')) {
        var exponentEnd = index + 1
        if (exponentEnd < end && (text[exponentEnd] == '+' || text[exponentEnd] == '-')) exponentEnd += 1
        val digitsStart = exponentEnd
        while (exponentEnd < end && text[exponentEnd].isDigit()) exponentEnd += 1
        if (exponentEnd > digitsStart) index = exponentEnd
    }
    return index
}

private fun findPlainScalarEnd(text: String, start: Int, end: Int): Int {
    var index = start + 1
    while (index < end) {
        val char = text[index]
        if (char.isWhitespace() || char == ',' || char == '[' || char == ']' || char == '{' || char == '}') break
        if (char == ':' && (index + 1 == end || text[index + 1].isWhitespace())) break
        index += 1
    }
    return index
}

private fun isTokenBoundary(text: String, index: Int, end: Int): Boolean =
    index >= end || text[index].isWhitespace() || text[index] in YAML_VALUE_BOUNDARIES

private val YAML_KEYWORDS = setOf("null", "true", "false", "yes", "no", "on", "off")
private val YAML_PUNCTUATION = setOf('[', ']', '{', '}', ',', ':', '&', '*', '!', '|', '>', '?', '-')
private val YAML_VALUE_BOUNDARIES = YAML_PUNCTUATION + setOf('#')
