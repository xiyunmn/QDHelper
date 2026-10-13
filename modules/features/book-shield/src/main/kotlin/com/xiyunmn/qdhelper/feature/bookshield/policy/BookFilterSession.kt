package com.xiyunmn.qdhelper.feature.bookshield.policy

import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.bookshield.model.BookInfo
import com.xiyunmn.qdhelper.feature.bookshield.model.BookInfoExtractionSpec
import java.util.IdentityHashMap
import java.util.Locale

internal class BookFilterSession(
    val location: String,
    val rules: BookShieldRules,
    traceEnabled: Boolean,
    source: Any?,
) {
    val extractionSpec = if (traceEnabled) BookInfoExtractionSpec.ALL else rules.extractionSpec
    val trace = BookFilterTrace(traceEnabled, location, source)
    private val filteredByIdentity = IdentityHashMap<Any, Any?>()

    fun hasResult(source: Any): Boolean = filteredByIdentity.containsKey(source)

    fun result(source: Any): Any? = filteredByIdentity[source]

    fun remember(source: Any, result: Any?) {
        filteredByIdentity[source] = result
    }
}

internal class BookFilterTrace(
    val enabled: Boolean,
    private val location: String,
    source: Any?,
) {
    private val startedAtNanos = System.nanoTime()
    private val sourceClass = source?.javaClass?.name
    private val sourceSize = (source as? List<*>)?.size ?: -1
    private val classes = LinkedHashMap<String, Int>()
    private val samples = ArrayList<String>(6)
    private var lists = 0
    private var emptyLists = 0
    private var items = 0
    private var removed = 0
    private var cards = 0
    private var widgets = 0

    fun onList(list: List<*>) {
        if (!enabled) return
        lists++
        if (list.isEmpty()) emptyLists++
    }

    fun onItem(item: Any?, info: BookInfo?, matched: Boolean) {
        if (!enabled) return
        items++
        val className = item?.javaClass?.simpleName ?: "null"
        if (classes.size < 12 || classes.containsKey(className)) {
            classes[className] = (classes[className] ?: 0) + 1
        }
        if (samples.size < 6) {
            samples += if (info == null) {
                "$className#noextract"
            } else {
                "${info.title}/${info.author}=$matched"
            }
        }
    }

    fun onRemoved(count: Int) {
        if (enabled) removed += count
    }

    fun onCard() {
        if (enabled) cards++
    }

    fun onWidget() {
        if (enabled) widgets++
    }

    fun emit() {
        if (!enabled) return
        val elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000.0
        FeatureHooks.i(
            "Shield trace batch: location=$location, class=$sourceClass, size=$sourceSize, " +
                "lists=$lists, empty=$emptyLists, items=$items, removed=$removed, cards=$cards, widgets=$widgets, " +
                "elapsedMs=${"%.3f".format(Locale.ROOT, elapsedMs)}, classes=$classes, " +
                "samples=[${samples.joinToString(" | ")}]",
        )
    }
}
