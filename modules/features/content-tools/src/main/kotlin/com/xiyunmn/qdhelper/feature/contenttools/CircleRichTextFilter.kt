package com.xiyunmn.qdhelper.feature.contenttools

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

internal object CircleRichTextFilter {
    private val textKeys = listOf("Text", "txt", "content", "value")
    private val labelKeys = listOf("NickName", "name", "title", "TopicName", "BookName", "label")
    private val fallbackTextPattern = Regex(
        """(?i)\"(?:text|txt|content|value)\"\s*:\s*\"((?:\\.|[^\"\\])*)\"""",
    )

    fun toPlainText(raw: String?): String {
        val source = raw.orEmpty().trim()
        if (source.isEmpty()) return ""
        parseJson(source)?.let { parsed ->
            return normalizeOutput(render(parsed))
        }
        if (looksStructured(source)) {
            val recovered = fallbackTextPattern.findAll(source)
                .map { match -> decodeJsonString(match.groupValues[1]) }
                .joinToString("") { value -> renderString(value) }
            return normalizeOutput(recovered)
        }
        return normalizeOutput(stripMarkup(source))
    }

    private fun render(value: Any?): String {
        return when (value) {
            null, JSONObject.NULL -> ""
            is JSONArray -> renderArray(value)
            is JSONObject -> renderObject(value)
            is String -> renderString(value)
            else -> ""
        }
    }

    private fun renderArray(value: JSONArray): String {
        return buildString {
            for (index in 0 until value.length()) {
                val item = value.opt(index)
                if (item is JSONObject || item is JSONArray) append(render(item))
            }
        }
    }

    private fun renderObject(value: JSONObject): String {
        val type = value.valueIgnoreCase("Type")?.toString()?.lowercase().orEmpty()
        val textValue = textKeys.firstNotNullOfOrNull { key -> value.valueIgnoreCase(key) }
        if (type == "10" || type == "at" || type == "mention") {
            val mention = render(textValue).trim()
            return when {
                mention.isEmpty() -> ""
                mention.startsWith("@") -> mention
                else -> "@$mention"
            }
        }
        if (textValue != null && textValue != JSONObject.NULL) {
            return render(textValue)
        }
        val labelEntry = labelKeys.firstNotNullOfOrNull { key ->
            value.valueIgnoreCase(key)?.takeUnless { it == JSONObject.NULL }
        }
        if (labelEntry != null) {
            val label = render(labelEntry)
            return if (value.hasKeyIgnoreCase("NickName") && label.isNotBlank()) "@$label" else label
        }
        return ""
    }

    private fun renderString(raw: String): String {
        val decoded = decodeUrlIfNeeded(raw)
        if (decoded.isBlank()) return ""
        if (looksStructured(decoded)) {
            parseJson(decoded)?.let { nested -> return render(nested) }
        }
        return stripMarkup(decoded)
    }

    private fun parseJson(value: String): Any? {
        if (!looksStructured(value)) return null
        return runCatching { JSONTokener(value).nextValue() }.getOrNull()
    }

    private fun looksStructured(value: String): Boolean {
        val trimmed = value.trimStart()
        return trimmed.startsWith("[") || trimmed.startsWith("{") ||
            (trimmed.startsWith("\"") && trimmed.endsWith("\""))
    }

    private fun decodeUrlIfNeeded(value: String): String {
        if (!value.contains('%')) return value
        return runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrDefault(value)
    }

    private fun decodeJsonString(value: String): String {
        return runCatching { JSONArray("[\"$value\"]").getString(0) }.getOrDefault(value)
    }

    private fun stripMarkup(value: String): String {
        return value
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</?(?:u|p|span|div)(?:\\s+[^>]*)?>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("\\r", "\n")
            .replace('\u2028', '\n')
    }

    private fun normalizeOutput(value: String): String {
        return value
            .replace("\u0000", "")
            .replace(Regex("[ \\t]+\\n"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun JSONObject.valueIgnoreCase(name: String): Any? {
        val keys = keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key.equals(name, ignoreCase = true)) return opt(key)
        }
        return null
    }

    private fun JSONObject.hasKeyIgnoreCase(name: String): Boolean {
        val keys = keys()
        while (keys.hasNext()) {
            if (keys.next().equals(name, ignoreCase = true)) return true
        }
        return false
    }
}
