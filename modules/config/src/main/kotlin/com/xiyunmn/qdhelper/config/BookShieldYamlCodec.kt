package com.xiyunmn.qdhelper.config

import org.snakeyaml.engine.v2.api.DumpSettings
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.api.lowlevel.Compose
import org.snakeyaml.engine.v2.api.lowlevel.Parse
import org.snakeyaml.engine.v2.api.lowlevel.Present
import org.snakeyaml.engine.v2.api.lowlevel.Serialize
import org.snakeyaml.engine.v2.common.FlowStyle
import org.snakeyaml.engine.v2.common.ScalarStyle
import org.snakeyaml.engine.v2.exceptions.MarkedYamlEngineException
import org.snakeyaml.engine.v2.events.AliasEvent
import org.snakeyaml.engine.v2.events.CollectionStartEvent
import org.snakeyaml.engine.v2.events.DocumentStartEvent
import org.snakeyaml.engine.v2.events.MappingEndEvent
import org.snakeyaml.engine.v2.events.MappingStartEvent
import org.snakeyaml.engine.v2.events.NodeEvent
import org.snakeyaml.engine.v2.events.ScalarEvent
import org.snakeyaml.engine.v2.events.SequenceEndEvent
import org.snakeyaml.engine.v2.events.SequenceStartEvent
import org.snakeyaml.engine.v2.nodes.MappingNode
import org.snakeyaml.engine.v2.nodes.Node
import org.snakeyaml.engine.v2.nodes.NodeTuple
import org.snakeyaml.engine.v2.nodes.ScalarNode
import org.snakeyaml.engine.v2.nodes.SequenceNode
import org.snakeyaml.engine.v2.nodes.Tag
import org.snakeyaml.engine.v2.schema.CoreSchema

object BookShieldYamlCodec {
    const val MAX_BYTES = 1024 * 1024

    private val topLevelFields = setOf(
        "version",
        "authors",
        "title_keywords",
        "full_titles",
        "types",
        "book_ids",
        "word_count",
    )
    private val loadSettings = LoadSettings.builder()
        .setLabel("rules.yaml")
        .setAllowDuplicateKeys(false)
        .setAllowRecursiveKeys(false)
        .setAllowNonScalarKeys(false)
        .setMaxAliasesForCollections(0)
        .setParseComments(true)
        .setCodePointLimit(MAX_BYTES)
        .setSchema(CoreSchema())
        .build()
    private val dumpSettings = DumpSettings.builder()
        .setDefaultFlowStyle(FlowStyle.BLOCK)
        .setDefaultScalarStyle(ScalarStyle.PLAIN)
        .setIndent(2)
        .setWidth(120)
        .setSplitLines(false)
        .setBestLineBreak("\n")
        .setDumpComments(true)
        .setDereferenceAliases(true)
        .setSchema(CoreSchema())
        .build()

    fun template(config: BookShieldRuleConfig = BookShieldRuleConfig.EMPTY): String = when (
        val result = rewrite(TEMPLATE, config)
    ) {
        is BookShieldYamlResult.Success -> result.text
        is BookShieldYamlResult.Failure -> error(result.error.displayMessage())
    }

    fun decode(text: String): BookShieldYamlResult = parseAndRewrite(text, replacement = null)

    fun rewrite(text: String, config: BookShieldRuleConfig): BookShieldYamlResult =
        parseAndRewrite(text, config)

    private fun parseAndRewrite(text: String, replacement: BookShieldRuleConfig?): BookShieldYamlResult {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            return BookShieldYamlResult.Failure(BookShieldYamlError("文件不能超过 1 MiB"))
        }
        val root = try {
            rejectUnsafeEvents(text)
            Compose(loadSettings).composeString(text).orElse(null)
                ?: return BookShieldYamlResult.Failure(BookShieldYamlError("配置文件不能为空"))
        } catch (failure: ValidationFailure) {
            return BookShieldYamlResult.Failure(failure.error)
        } catch (throwable: Throwable) {
            return BookShieldYamlResult.Failure(throwable.toYamlError())
        }
        return try {
            rejectAnchorsAndUnsupportedTags(root)
            val mapping = root as? MappingNode ?: fail("根节点必须是映射", null, root)
            val entries = mapping.entries("", topLevelFields)
            val version = entries["version"] ?: fail("缺少 version", "version", mapping)
            requireDecimalScalar(version, "version").takeIf { it == 1L }
                ?: fail("仅支持 version: 1", "version", version)

            val parsed = BookShieldRuleConfig(
                authors = readTextList(entries["authors"], "authors"),
                titleKeywords = readTextList(entries["title_keywords"], "title_keywords"),
                fullTitles = readTextList(entries["full_titles"], "full_titles"),
                types = readTextList(entries["types"], "types"),
                bookIds = readBookIds(entries["book_ids"]),
                wordsAtMost = readWordBound(entries["word_count"], "at_most"),
                wordsAtLeast = readWordBound(entries["word_count"], "at_least"),
            ).normalized()
            val target = (replacement ?: parsed).normalized()
            val canonicalRoot = canonicalRoot(mapping, entries, target)
            BookShieldYamlResult.Success(target, dump(canonicalRoot))
        } catch (failure: ValidationFailure) {
            BookShieldYamlResult.Failure(failure.error)
        } catch (throwable: IllegalArgumentException) {
            BookShieldYamlResult.Failure(BookShieldYamlError(throwable.message ?: "规则无效"))
        } catch (throwable: Throwable) {
            BookShieldYamlResult.Failure(throwable.toYamlError())
        }
    }

    private fun readTextList(node: Node?, path: String): List<String> {
        if (node == null) return emptyList()
        val sequence = node as? SequenceNode ?: fail("必须是列表", path, node)
        // 一个列表项就是一条规则：书名里的逗号、空格等标点属于内容，绝不在此拆分。
        return sequence.value.mapIndexed { index, item ->
            val scalar = item as? ScalarNode ?: fail("列表项必须是普通标量", "$path[$index]", item)
            if (scalar.scalarStyle == ScalarStyle.LITERAL || scalar.scalarStyle == ScalarStyle.FOLDED) {
                fail("不支持块标量，请使用普通文本或引号", "$path[$index]", scalar)
            }
            if (scalar.tag != Tag.STR && scalar.tag != Tag.INT) {
                fail("必须是文本；true、null 等值请加引号", "$path[$index]", scalar)
            }
            BookShieldRuleConfig.normalizeDisplay(scalar.value)
                .takeIf(String::isNotEmpty) ?: fail("不能是空文本", "$path[$index]", scalar)
        }
    }

    private fun readBookIds(node: Node?): List<Long> {
        if (node == null) return emptyList()
        val sequence = node as? SequenceNode ?: fail("必须是列表", "book_ids", node)
        return sequence.value.mapIndexed { index, item ->
            val scalar = item as? ScalarNode ?: fail("必须是正整数", "book_ids[$index]", item)
            val value = scalar.value.trim()
            if ((scalar.tag != Tag.INT && scalar.tag != Tag.STR) || !DECIMAL.matches(value)) {
                fail("必须是十进制正整数", "book_ids[$index]", scalar)
            }
            value.toLongOrNull()?.takeIf { it > 0L }
                ?: fail("必须是 Long 范围内的正整数", "book_ids[$index]", scalar)
        }.distinct()
    }

    private fun readWordBound(wordCount: Node?, key: String): Long? {
        if (wordCount == null) return null
        val mapping = wordCount as? MappingNode ?: fail("必须是映射", "word_count", wordCount)
        val entries = mapping.entries("word_count", setOf("at_most", "at_least"))
        val node = entries[key] ?: return null
        val scalar = node as? ScalarNode ?: fail("必须是正整数或 null", "word_count.$key", node)
        if (scalar.tag == Tag.NULL) return null
        val value = scalar.value.trim()
        if ((scalar.tag != Tag.INT && scalar.tag != Tag.STR) || !DECIMAL.matches(value)) {
            fail("必须是十进制正整数或 null", "word_count.$key", scalar)
        }
        return value.toLongOrNull()?.takeIf { it > 0L }
            ?: fail("必须是 Long 范围内的正整数或 null", "word_count.$key", scalar)
    }

    private fun canonicalRoot(
        source: MappingNode,
        entries: Map<String, Node>,
        config: BookShieldRuleConfig,
    ): MappingNode {
        val sourceTuples = source.value.associateBy { (it.keyNode as ScalarNode).value }
        fun tuple(key: String, value: Node): NodeTuple {
            val old = sourceTuples[key]
            return NodeTuple(old?.keyNode ?: scalar(Tag.STR, key), value.copyCommentsFrom(old?.valueNode))
        }
        val wordSource = entries["word_count"] as? MappingNode
        val wordEntries = wordSource?.value.orEmpty().associateBy { (it.keyNode as ScalarNode).value }
        fun wordTuple(key: String, value: Long?): NodeTuple {
            val old = wordEntries[key]
            val node = if (value == null) scalar(Tag.NULL, "null") else scalar(Tag.INT, value.toString())
            return NodeTuple(old?.keyNode ?: scalar(Tag.STR, key), node.copyCommentsFrom(old?.valueNode))
        }
        val wordNode = MappingNode(
            Tag.MAP,
            listOf(wordTuple("at_most", config.wordsAtMost), wordTuple("at_least", config.wordsAtLeast)),
            wordSource?.flowStyle ?: FlowStyle.BLOCK,
        ).copyCommentsFrom(wordSource)
        val result = MappingNode(
            Tag.MAP,
            listOf(
                tuple("version", scalar(Tag.INT, "1")),
                tuple("authors", textSequence(entries["authors"], config.authors)),
                tuple("title_keywords", textSequence(entries["title_keywords"], config.titleKeywords)),
                tuple("full_titles", textSequence(entries["full_titles"], config.fullTitles)),
                tuple("types", textSequence(entries["types"], config.types)),
                tuple("book_ids", idSequence(entries["book_ids"], config.bookIds)),
                tuple("word_count", wordNode),
            ),
            source.flowStyle,
        )
        return result.copyCommentsFrom(source)
    }

    // 列表一律以 BLOCK 风格回写（空列表由 emitter 自动降级为 []），避免 flow 上下文里的分隔歧义。
    private fun textSequence(source: Node?, values: List<String>): SequenceNode {
        val old = (source as? SequenceNode)?.value.orEmpty()
            .filterIsInstance<ScalarNode>()
            .associateBy { BookShieldRuleConfig.normalizeIdentity(it.value) }
        val nodes = values.map { value ->
            val previous = old[BookShieldRuleConfig.normalizeIdentity(value)]
            scalar(Tag.STR, value, previous?.scalarStyle ?: ScalarStyle.PLAIN).copyCommentsFrom(previous)
        }
        return SequenceNode(Tag.SEQ, nodes, FlowStyle.BLOCK).copyCommentsFrom(source)
    }

    private fun idSequence(source: Node?, values: List<Long>): SequenceNode {
        val old = (source as? SequenceNode)?.value.orEmpty()
            .filterIsInstance<ScalarNode>()
            .associateBy { it.value.trim().toLongOrNull() }
        val nodes = values.map { value -> scalar(Tag.INT, value.toString()).copyCommentsFrom(old[value]) }
        return SequenceNode(Tag.SEQ, nodes, FlowStyle.BLOCK).copyCommentsFrom(source)
    }

    private fun MappingNode.entries(path: String, allowed: Set<String>): Map<String, Node> {
        val result = linkedMapOf<String, Node>()
        value.forEach { tuple ->
            val key = tuple.keyNode as? ScalarNode ?: fail("键必须是普通文本", path.ifEmpty { null }, tuple.keyNode)
            if (key.tag == Tag.MERGE || key.value == "<<") fail("不支持合并键", childPath(path, key.value), key)
            if (key.tag != Tag.STR) fail("键必须是文本", path.ifEmpty { null }, key)
            if (key.value !in allowed) fail("未知字段 ${key.value}", childPath(path, key.value), key)
            if (result.put(key.value, tuple.valueNode) != null) fail("字段重复", childPath(path, key.value), key)
        }
        return result
    }

    private fun rejectAnchorsAndUnsupportedTags(node: Node) {
        if (node.anchor.isPresent) fail("不支持锚点或别名", null, node)
        when (node) {
            is MappingNode -> node.value.forEach {
                rejectAnchorsAndUnsupportedTags(it.keyNode)
                rejectAnchorsAndUnsupportedTags(it.valueNode)
            }
            is SequenceNode -> node.value.forEach(::rejectAnchorsAndUnsupportedTags)
            is ScalarNode -> if (node.tag !in ALLOWED_SCALAR_TAGS) fail("不支持自定义标签", null, node)
        }
        if (node is MappingNode && node.tag != Tag.MAP) fail("不支持自定义标签", null, node)
        if (node is SequenceNode && node.tag != Tag.SEQ) fail("不支持自定义标签", null, node)
    }

    private fun rejectUnsafeEvents(text: String) {
        var documents = 0
        val containers = java.util.ArrayDeque<EventContainer>()
        fun completeNode() {
            containers.peekLast()?.takeIf(EventContainer::mapping)?.let { parent ->
                parent.expectingKey = !parent.expectingKey
            }
        }
        Parse(loadSettings).parseString(text).forEach { event ->
            if (event is DocumentStartEvent) {
                documents += 1
                if (documents > 1) failEvent("不支持多文档 YAML", event.startMark.orElse(null))
                if (event.tags.isNotEmpty()) failEvent("不支持 TAG 指令", event.startMark.orElse(null))
            }
            if (event is AliasEvent) failEvent("不支持别名", event.startMark.orElse(null))
            if (event is NodeEvent && event.anchor.isPresent) {
                failEvent("不支持锚点", event.startMark.orElse(null))
            }
            if (event is ScalarEvent && event.tag.isPresent) {
                failEvent("不支持显式标签", event.startMark.orElse(null))
            }
            if (event is CollectionStartEvent && event.tag.isPresent) {
                failEvent("不支持显式标签", event.startMark.orElse(null))
            }
            when (event) {
                is ScalarEvent -> {
                    if (containers.peekLast()?.let { it.mapping && it.expectingKey } == true && event.value == "<<") {
                        failEvent("不支持合并键", event.startMark.orElse(null))
                    }
                    completeNode()
                }
                is MappingStartEvent -> containers.addLast(EventContainer(mapping = true))
                is SequenceStartEvent -> containers.addLast(EventContainer(mapping = false))
                is MappingEndEvent, is SequenceEndEvent -> {
                    if (containers.isNotEmpty()) containers.removeLast()
                    completeNode()
                }
            }
        }
    }

    private fun requireDecimalScalar(node: Node, path: String): Long {
        val scalar = node as? ScalarNode ?: fail("必须是整数", path, node)
        if (scalar.tag != Tag.INT || !DECIMAL.matches(scalar.value.trim())) fail("必须是十进制整数", path, scalar)
        return scalar.value.trim().toLongOrNull() ?: fail("整数超出范围", path, scalar)
    }

    private fun dump(node: Node): String {
        val events = Serialize(dumpSettings).serializeOne(node)
        return Present(dumpSettings).emitToString(events.iterator()).trimEnd() + "\n"
    }

    private fun scalar(tag: Tag, value: String, style: ScalarStyle = ScalarStyle.PLAIN): ScalarNode =
        ScalarNode(tag, value, style)

    private fun <T : Node> T.copyCommentsFrom(source: Node?): T = apply {
        blockComments = source?.blockComments
        inLineComments = source?.inLineComments
        endComments = source?.endComments
    }

    private fun fail(message: String, path: String?, node: Node?): Nothing {
        val mark = node?.startMark?.orElse(null)
        throw ValidationFailure(
            BookShieldYamlError(message, path, mark?.line?.plus(1), mark?.column?.plus(1)),
        )
    }

    private fun failEvent(message: String, mark: org.snakeyaml.engine.v2.exceptions.Mark?): Nothing {
        throw ValidationFailure(
            BookShieldYamlError(message, line = mark?.line?.plus(1), column = mark?.column?.plus(1)),
        )
    }

    private fun childPath(parent: String, child: String): String = if (parent.isEmpty()) child else "$parent.$child"

    private fun Throwable.toYamlError(): BookShieldYamlError {
        val marked = this as? MarkedYamlEngineException
        val mark = marked?.problemMark?.orElse(null) ?: marked?.contextMark?.orElse(null)
        val detail = marked?.problem?.takeIf { it.isNotBlank() }
            ?: message?.lineSequence()?.firstOrNull()?.trim()?.takeIf(String::isNotEmpty)
            ?: javaClass.simpleName
        return BookShieldYamlError(detail, line = mark?.line?.plus(1), column = mark?.column?.plus(1))
    }

    private class ValidationFailure(val error: BookShieldYamlError) : RuntimeException(error.message)

    private data class EventContainer(
        val mapping: Boolean,
        var expectingKey: Boolean = true,
    )

    private val DECIMAL = Regex("[0-9]+")
    private val ALLOWED_SCALAR_TAGS = setOf(Tag.STR, Tag.INT, Tag.FLOAT, Tag.BOOL, Tag.NULL)

    private const val TEMPLATE = """# QDHelper 书籍屏蔽规则（YAML v1）
# 文本规则会忽略大小写，并在保存时按规范化结果去重。
# 每行一条规则；书名里的逗号、冒号、空格都属于内容，不会被拆成多条。
version: 1

# 精确匹配作者名。
authors: []

# 书名包含任一关键词时屏蔽。
title_keywords: []

# 精确匹配完整书名。
full_titles: []

# 匹配分类、子分类、标签、类型文本或类型码。
# 是否使用包含匹配由设置页的“类型增强匹配”控制。
types: []

# 仅接受十进制正整数。
book_ids: []

word_count:
  # 屏蔽字数小于等于该值；null 表示关闭。
  at_most: null
  # 屏蔽字数大于等于该值；null 表示关闭。
  at_least: null
"""
}
