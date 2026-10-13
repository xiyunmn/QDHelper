package com.xiyunmn.qdhelper.feature.bookshield.policy

import com.xiyunmn.qdhelper.feature.bookshield.model.BookInfo
import com.xiyunmn.qdhelper.feature.bookshield.model.BookInfoExtractionSpec
import com.xiyunmn.qdhelper.feature.bookshield.model.BookInfoExtractor
import com.xiyunmn.qdhelper.feature.bookshield.model.categoryForStatistics
import com.xiyunmn.qdhelper.feature.bookshield.stats.BookShieldStats
import com.xiyunmn.qdhelper.feature.bookshield.stats.ShieldEvent
import android.content.Context
import com.xiyunmn.qdhelper.config.BookShieldRuleOperationResult
import com.xiyunmn.qdhelper.config.BookShieldRuleRepository
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import java.util.ArrayList

object BookListFilter {
    private val nestedListGetters = listOf(
        "getList",
        "getItems",
        "getBooks",
        "getMultiData",
        "getBookList",
        "getBookListData",
        "getRankBookList",
        "getRankData",
        "getRankDataSingle",
        "getTopicRankData",
        "getTopicRecommendData",
        "getComicSqureRecmdItems",
        "getComicSquareUpdateItems",
        "getImageTextList",
    )
    private val nestedListFields = listOf(
        "BookList",
        "RecommendItem",
        "currentClassicsItem",
        "rankBookList",
        "comicSqureRecmdItems",
        "comicSquareUpdateItems",
    )
    private val nestedObjectGetters = listOf(
        "getBookListData",
        "getRankData",
        "getRankDataSingle",
        "getTopicRankData",
        "getTopicRecommendData",
    )
    private val nestedObjectFields = listOf(
        "bookListData",
        "rankData",
        "rankDataSingle",
        "topicRankData",
        "topicRecommendData",
    )

    fun filterSource(context: Context?, location: String, source: Any?): Any? {
        context ?: return source
        if (!SettingsRuntime.isBookShieldLocationEnabled(context, location)) return source
        if (source is List<*> && source.isEmpty()) return source
        val rules = BookShieldRules.from(context)
        if (!rules.hasRules) return source
        val trace = SettingsRuntime.isShieldTraceEnabled(context)
        return filterSourceWithRules(location, source, rules, trace)
    }

    internal fun filterSourceWithRules(
        location: String,
        source: Any?,
        rules: BookShieldRules,
        trace: Boolean = false,
    ): Any? {
        if (source is List<*> && source.isEmpty()) return source
        if (!rules.hasRules) return source
        val session = BookFilterSession(location, rules, trace, source)
        val result = runCatching {
            when (source) {
                is List<*> -> filterList(session, source)
                else -> filterContainer(session, source)
            }
        }.getOrElse { throwable ->
            FeatureHooks.w("Book shield filter failed: location=$location, error=${throwable.javaClass.name}")
            source
        }
        session.trace.emit()
        return result
    }

    fun addFullTitle(context: Context, title: String): Boolean {
        val normalized = title.trim()
        if (normalized.isEmpty()) return false
        return updateTextRules(context, normalized, TextRuleField.FULL_TITLE, add = true)
    }

    fun hasFullTitle(context: Context, title: String): Boolean {
        val target = BookShieldRules.normalize(title)
        return BookShieldRuleRepository.snapshot(context).fullTitles.any { BookShieldRules.normalize(it) == target }
    }

    fun removeFullTitle(context: Context, title: String): Boolean {
        return updateTextRules(context, title, TextRuleField.FULL_TITLE, add = false)
    }

    fun addAuthor(context: Context, author: String): Boolean {
        val normalized = author.trim()
        if (normalized.isEmpty()) return false
        return updateTextRules(context, normalized, TextRuleField.AUTHOR, add = true)
    }

    fun hasAuthor(context: Context, author: String): Boolean {
        val target = BookShieldRules.normalize(author)
        return BookShieldRuleRepository.snapshot(context).authors.any { BookShieldRules.normalize(it) == target }
    }

    fun removeAuthor(context: Context, author: String): Boolean {
        return updateTextRules(context, author, TextRuleField.AUTHOR, add = false)
    }

    fun addBookId(context: Context, bookId: Long): Boolean {
        if (bookId <= 0L) return false
        return BookShieldRuleRepository.update(context) { current ->
            current.copy(bookIds = current.bookIds + bookId)
        } is BookShieldRuleOperationResult.Success
    }

    fun hasBookId(context: Context, bookId: Long): Boolean {
        return bookId > 0L && BookShieldRuleRepository.snapshot(context).bookIds.contains(bookId)
    }

    fun removeBookId(context: Context, bookId: Long): Boolean {
        if (bookId <= 0L) return false
        return BookShieldRuleRepository.update(context) { current ->
            current.copy(bookIds = current.bookIds.filterNot { it == bookId })
        } is BookShieldRuleOperationResult.Success
    }

    private fun updateTextRules(context: Context, token: String, field: TextRuleField, add: Boolean): Boolean {
        val target = BookShieldRules.normalize(token)
        if (target.isEmpty()) return false
        return BookShieldRuleRepository.update(context) { current ->
            val source = when (field) {
                TextRuleField.AUTHOR -> current.authors
                TextRuleField.FULL_TITLE -> current.fullTitles
            }
            val next = if (add) {
                if (source.any { BookShieldRules.normalize(it) == target }) source else source + token.trim()
            } else {
                source.filterNot { BookShieldRules.normalize(it) == target }
            }
            when (field) {
                TextRuleField.AUTHOR -> current.copy(authors = next)
                TextRuleField.FULL_TITLE -> current.copy(fullTitles = next)
            }
        } is BookShieldRuleOperationResult.Success
    }

    private enum class TextRuleField { AUTHOR, FULL_TITLE }

    @Suppress("UNCHECKED_CAST")
    private fun filterList(
        session: BookFilterSession,
        list: List<*>,
    ): Any? {
        if (session.hasResult(list)) return session.result(list)
        session.remember(list, list)
        session.trace.onList(list)
        if (list.isEmpty()) return list
        var changed = false
        // Collect stats candidates but only commit them once the removal actually takes effect:
        // if the empty-result guard reverts the whole list below, the matched books stay visible
        // and must NOT be counted as shielded (that was the over-count found in review).
        val pendingEvents = ArrayList<ShieldEvent>()
        val next = ArrayList<Any?>(list.size)
        list.forEach { item ->
            val directInfo = BookInfoExtractor.extract(item, session.extractionSpec)
            val nestedBook = if (directInfo == null) {
                extractSingleMorphingCardBook(item, session.extractionSpec)
            } else {
                null
            }
            val info = directInfo ?: nestedBook?.info
            val reason = if (info != null) session.rules.matchReason(info) else null
            session.trace.onItem(item, info, reason != null)
            if (info != null && reason != null) {
                val eventSource = nestedBook?.source ?: item
                val eventInfo = if (session.extractionSpec.isComplete) {
                    info
                } else {
                    BookInfoExtractor.extract(eventSource) ?: info
                }
                changed = true
                pendingEvents += ShieldEvent(
                    timestamp = System.currentTimeMillis(),
                    location = session.location,
                    reasonKind = reason.kind,
                    reasonDetail = reason.detail,
                    bookId = eventInfo.id,
                    title = eventInfo.title,
                    author = eventInfo.author,
                    category = eventInfo.categoryForStatistics(),
                    tags = eventInfo.tags,
                    words = eventInfo.words,
                    source = eventInfo.sourceClass,
                )
                return@forEach
            }
            if (directInfo != null && isLeafBookItem(item, directInfo)) {
                next += item
                return@forEach
            }
            val containerResult = filterContainer(session, item)
            if (containerResult !== item) changed = true
            next += containerResult
        }
        if (!changed) return list
        if (next.isEmpty() && !allowsEmptyResult(session)) {
            // Reverted to the original list to avoid an empty section — nothing was actually
            // hidden, so drop the pending stats rather than over-count them.
            record(session.location, list.size, 0, "skip-empty-result")
            return list
        }
        // The filtered list is returned to (and applied by) the caller: commit stats now.
        pendingEvents.forEach { BookShieldStats.record(it) }
        session.trace.onRemoved(pendingEvents.size)
        record(session.location, list.size, pendingEvents.size, "filtered")
        val filtered = if (list is ArrayList<*>) ArrayList(next) else next
        session.remember(list, filtered)
        return filtered
    }

    /**
     * Selection and category waterfalls represent each visible book as one MorphingCard whose
     * data list contains exactly one MorphingItem. Filtering only the inner singleton list is
     * defeated by the global empty-result guard, so lift that book to the card level and remove
     * the whole card when it matches. Multi-book cards continue through the normal recursive path.
     */
    private fun extractSingleMorphingCardBook(
        source: Any?,
        spec: BookInfoExtractionSpec,
    ): ExtractedBook? {
        if (source?.javaClass?.name != HostSymbols.Classes.MORPHING_CARD) return null
        val data = BookInfoExtractor.invokeNoArg(source, "getData") ?: return null
        val items = BookInfoExtractor.invokeNoArg(data, "getList") as? List<*> ?: return null
        if (items.size != 1) return null
        val book = items.singleOrNull() ?: return null
        val info = BookInfoExtractor.extract(book, spec) ?: return null
        if (info.title.isNullOrBlank() && info.id == null) return null
        return ExtractedBook(source = book, info = info)
    }

    private data class ExtractedBook(
        val source: Any,
        val info: BookInfo,
    )

    /**
     * The last-page "more reading" feed supplies one book per nested Morphing list. Keeping the
     * global empty-section guard for that nested list would restore every matched book, so this
     * location must be allowed to become empty. Other locations retain the user's configured
     * empty-result behavior.
     */
    private fun allowsEmptyResult(session: BookFilterSession): Boolean {
        return session.rules.allowEmptyResult ||
            session.location == BookShieldLocations.READ_LAST_PAGE_READ_MORE
    }

    private fun isLeafBookItem(source: Any?, info: BookInfo): Boolean {
        val hasBookIdentity = !info.title.isNullOrBlank() || info.id != null
        if (!hasBookIdentity) return false
        return !BookInfoExtractor.hasAnyAccessor(
            source = source,
            methodNames = nestedObjectGetters + nestedListGetters,
            fieldNames = nestedObjectFields + nestedListFields,
        )
    }

    private fun filterContainer(
        session: BookFilterSession,
        source: Any?,
    ): Any? {
        source ?: return source
        if (session.hasResult(source)) return session.result(source)
        session.remember(source, source)
        val copied = copyMorphingCardIfNeeded(source, session)
        if (copied !== source) {
            session.remember(source, copied)
            return copied
        }
        val copiedWidget = copyMorphingWidgetDataIfNeeded(source, session)
        if (copiedWidget !== source) {
            session.remember(source, copiedWidget)
            return copiedWidget
        }

        nestedObjectGetters.forEach { getter ->
            val value = BookInfoExtractor.invokeNoArg(source, getter) ?: return@forEach
            val filtered = filterContainer(session, value)
            if (filtered !== value) {
                if (!trySetBySetter(source, getter, filtered)) {
                    trySetByFieldName(source, getter, filtered)
                }
            }
        }
        nestedObjectFields.forEach { fieldName ->
            val value = BookInfoExtractor.fieldValue(source, fieldName) ?: return@forEach
            val filtered = filterContainer(session, value)
            if (filtered !== value) {
                BookInfoExtractor.field(source, fieldName)?.let { field ->
                    runCatching {
                        field.set(source, filtered)
                    }
                }
            }
        }
        nestedListGetters.forEach { getter ->
            val value = BookInfoExtractor.invokeNoArg(source, getter) as? List<*> ?: return@forEach
            val filtered = filterList(session, value)
            if (filtered !== value && filtered is List<*>) {
                if (!trySetBySetter(source, getter, filtered) && !trySetByFieldName(source, getter, filtered)) {
                    val copiedContainer = copyKnownContainerWithList(source, getter, filtered)
                    if (copiedContainer !== source) {
                        session.remember(source, copiedContainer)
                        return copiedContainer
                    }
                }
            }
        }
        nestedListFields.forEach { fieldName ->
            val value = BookInfoExtractor.fieldValue(source, fieldName) as? List<*> ?: return@forEach
            val filtered = filterList(session, value)
            if (filtered !== value && filtered is List<*>) {
                BookInfoExtractor.field(source, fieldName)?.let { field ->
                    runCatching {
                        field.set(source, ArrayList(filtered))
                    }
                }
            }
        }
        return source
    }

    private fun copyMorphingCardIfNeeded(
        source: Any,
        session: BookFilterSession,
    ): Any? {
        if (source.javaClass.name != HostSymbols.Classes.MORPHING_CARD) return source
        val data = BookInfoExtractor.invokeNoArg(source, "getData") ?: return source
        session.trace.onCard()
        val copyData = copyWidgetDataWithFilteredLists(data, session) ?: return source
        if (copyData === data) return source
        val method = BookInfoExtractor.method(source, "copy", 6) ?: BookInfoExtractor.method(source, "copy", 3) ?: return source
        return runCatching {
            if (method.parameterTypes.size == 6) {
                method.invoke(
                    source,
                    BookInfoExtractor.invokeNoArg(source, "getViewType") as? Int ?: 0,
                    BookInfoExtractor.invokeNoArg(source, "getAttr"),
                    copyData,
                    BookInfoExtractor.invokeNoArg(source, "getGroupId") as? Int ?: -1,
                    BookInfoExtractor.invokeNoArg(source, "getTrackViewType") as? Int ?: 0,
                    BookInfoExtractor.invokeNoArg(source, "getTabIndex") as? Int ?: 0,
                )
            } else {
                method.invoke(
                    source,
                    BookInfoExtractor.invokeNoArg(source, "getViewType") as? Int ?: 0,
                    BookInfoExtractor.invokeNoArg(source, "getAttr"),
                    copyData,
                )
            }
        }.getOrElse {
            source
        }
    }

    private fun copyMorphingWidgetDataIfNeeded(
        source: Any,
        session: BookFilterSession,
    ): Any? {
        if (source.javaClass.name != HostSymbols.Classes.MORPHING_WIDGET_DATA) return source
        return copyWidgetDataWithFilteredLists(source, session)
    }

    private fun copyWidgetDataWithFilteredLists(
        data: Any,
        session: BookFilterSession,
    ): Any? {
        val method = BookInfoExtractor.method(data, "copy", 20) ?: BookInfoExtractor.method(data, "copy", 10) ?: return data
        val list = BookInfoExtractor.invokeNoArg(data, "getList") as? List<*> ?: emptyList<Any?>()
        val multiData = BookInfoExtractor.invokeNoArg(data, "getMultiData") as? List<*> ?: emptyList<Any?>()
        val bookList = BookInfoExtractor.invokeNoArg(data, "getBookList") as? List<*>
        val imageTextList = BookInfoExtractor.invokeNoArg(data, "getImageTextList") as? List<*>
        val audioList = BookInfoExtractor.invokeNoArg(data, "getAudioList") as? List<*>
        session.trace.onWidget()
        val filteredList = filterList(session, list) as? List<*> ?: list
        val filteredMultiData = filterList(session, multiData) as? List<*> ?: multiData
        val filteredBookList = bookList?.let { filterList(session, it) as? List<*> ?: it }
        val filteredImageTextList = imageTextList?.let { filterList(session, it) as? List<*> ?: it }
        val filteredAudioList = audioList?.let { filterList(session, it) as? List<*> ?: it }
        if (filteredList === list && filteredMultiData === multiData && filteredBookList === bookList && filteredImageTextList === imageTextList && filteredAudioList === audioList) {
            return data
        }
        return runCatching {
            if (method.parameterTypes.size == 20) {
                method.invoke(
                    data,
                    BookInfoExtractor.invokeNoArg(data, "getCardTitle"),
                    BookInfoExtractor.invokeNoArg(data, "getType") as? Int ?: 0,
                    BookInfoExtractor.invokeNoArg(data, "getSelectId") as? String,
                    BookInfoExtractor.invokeNoArg(data, "getSelectName") as? String,
                    BookInfoExtractor.invokeNoArg(data, "getSelectIcon") as? String,
                    BookInfoExtractor.invokeNoArg(data, "getSelectTip") as? String,
                    BookInfoExtractor.invokeNoArg(data, "getPosition") as? Int ?: 0,
                    BookInfoExtractor.invokeNoArg(data, "getExtension"),
                    filteredList,
                    filteredMultiData,
                    BookInfoExtractor.invokeNoArg(data, "getNodeId") as? Int ?: 0,
                    BookInfoExtractor.invokeNoArg(data, "getTabs"),
                    BookInfoExtractor.invokeNoArg(data, "getListType") as? String,
                    filteredBookList,
                    filteredImageTextList,
                    filteredAudioList,
                    BookInfoExtractor.invokeNoArg(data, "getInnerIndex") as? Int ?: -1,
                    BookInfoExtractor.invokeNoArg(data, "getOtherMap"),
                    BookInfoExtractor.invokeNoArg(data, "getShowOrder") as? Int ?: 0,
                    BookInfoExtractor.invokeNoArg(data, "getItemStyle") as? Int ?: 0,
                )
            } else {
                method.invoke(
                    data,
                    BookInfoExtractor.invokeNoArg(data, "getCardTitle"),
                    BookInfoExtractor.invokeNoArg(data, "getType") as? Int ?: 0,
                    BookInfoExtractor.invokeNoArg(data, "getSelectId") as? String,
                    BookInfoExtractor.invokeNoArg(data, "getSelectName") as? String,
                    BookInfoExtractor.invokeNoArg(data, "getSelectIcon") as? String,
                    BookInfoExtractor.invokeNoArg(data, "getSelectTip") as? String,
                    BookInfoExtractor.invokeNoArg(data, "getPosition") as? Int ?: 0,
                    BookInfoExtractor.invokeNoArg(data, "getExtension"),
                    filteredList,
                    filteredMultiData,
                )
            }
        }.getOrDefault(data)
    }

    private fun trySetBySetter(source: Any, getterName: String, value: Any?): Boolean {
        val suffix = getterName.removePrefix("get")
        val setter = BookInfoExtractor.method(source, "set$suffix", 1) ?: return false
        return runCatching {
            setter.invoke(source, if (value is List<*>) ArrayList(value) else value)
            true
        }.getOrDefault(false)
    }

    private fun trySetByFieldName(source: Any, getterName: String, value: Any?): Boolean {
        val suffix = getterName.removePrefix("get")
        val candidates = listOf(suffix.replaceFirstChar { it.lowercase() }, suffix)
        candidates.forEach { fieldName ->
            val field = BookInfoExtractor.field(source, fieldName) ?: return@forEach
            if (runCatching { field.set(source, if (value is List<*>) ArrayList(value) else value); true }.getOrDefault(false)) return true
        }
        return false
    }

    private fun copyKnownContainerWithList(source: Any, getterName: String, value: List<*>): Any? {
        val className = source.javaClass.name
        val method = when {
            className.endsWith(".SimilarRecommend") && getterName == "getBookList" ->
                BookInfoExtractor.method(source, "copy", 1)
            className.endsWith(".BookListData") && getterName == "getItems" -> BookInfoExtractor.method(source, "copy", 1)
            className.endsWith(".RankDataWrap") && getterName == "getItems" -> BookInfoExtractor.method(source, "copy", 1)
            className.endsWith(".TopicRankList") && getterName == "getItems" -> BookInfoExtractor.method(source, "copy", 1)
            className.endsWith(".TopicRecommendList") && getterName == "getItems" -> BookInfoExtractor.method(source, "copy", 1)
            className.endsWith(".RankData") && getterName == "getBooks" -> BookInfoExtractor.method(source, "copy", 4)
            else -> null
        } ?: return source
        return runCatching {
            if (method.parameterTypes.size == 1) {
                method.invoke(source, value)
            } else {
                method.invoke(
                    source,
                    BookInfoExtractor.invokeNoArg(source, "getRankId") as? String,
                    BookInfoExtractor.invokeNoArg(source, "getRankName") as? String,
                    BookInfoExtractor.invokeNoArg(source, "getDesc") as? String,
                    value,
                )
            }
        }.getOrDefault(source)
    }

    private fun record(location: String, before: Int, removed: Int, action: String) {
        val summary = "location=$location, action=$action, before=$before, removed=$removed"
        FeatureHooks.i("Book shield: $summary")
    }
}
