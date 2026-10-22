package com.xiyunmn.qdhelper.feature.pagecustomization

import com.xiyunmn.qdhelper.feature.api.runtime.UiFeatureSupport
import android.content.Context
import android.view.View
import android.view.ViewGroup
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.config.SettingIds
import java.lang.reflect.Method
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import com.xiyunmn.qdhelper.host.api.gateway.NativeNetRequest
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols as QDReaderHost
object SelectionPageCustomization {
    private const val MORPHING_ADAPTER = QDReaderHost.Classes.MORPHING_ADAPTER
    private const val MORPHING_CARD = QDReaderHost.Classes.MORPHING_CARD
    private const val MAX_SCAN_CARDS = 300
    private const val ENTRANCE_VIEW_TYPE = 3
    private const val ENTRANCE_GRID_TYPE = 1
    private const val ENTRANCE_TICKER_TYPE = 2
    private const val DISABLED_GUESS_SECTION_LABEL = "猜你喜欢"
    private const val DISABLED_GUESS_SECTION_MODEL = "yuedupianhao"
    private const val FLUTTER_SELECTION_PATH = "/argus/api/v1/booksquare/getsquarepagepiece"

    private val rankViewTypes = setOf(13, 27, 32, 47)
    private val disabledGuessSectionTokens = setOf(
        DISABLED_GUESS_SECTION_LABEL,
        DISABLED_GUESS_SECTION_MODEL,
    )
    @Volatile private var latestFlutterSelectionPayload: String? = null
    // 23 is the Flutter selection site observed on 7.9.482; native selection uses the others.
    private val selectionSites = setOf(11, 12, 14, 18, 20, 23)
    private val reportedFlutterScopes = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val reportedFlutterFilters = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    data class AdapterProcessResult(
        val replacementList: List<Any?>?,
        val changed: Boolean,
    )

    private data class HiddenSelection(
        val keys: Set<String>,
        val legacyLabels: Set<String>,
    ) {
        fun matches(item: PageCustomItem): Boolean {
            return item.key in keys || item.label in legacyLabels
        }

        fun isEmpty(): Boolean {
            return keys.isEmpty() && legacyLabels.isEmpty()
        }
    }

    fun isDisabledItem(item: PageCustomItem): Boolean {
        return isDisabledGuessSectionValue(item.label)
    }

    /**
     * Put the 精选 page refresh layout into a terminal state so the infinite 猜你喜欢 load-more stops
     * without a hung spinner. Called from the fetchMorePageData gate (fragment as thisObject).
     */
    fun stopLoadMore(fragment: Any?) {
        fragment ?: return
        runCatching {
            val root = UiFeatureSupport.rootViewOf(fragment) ?: return
            val id = root.resources.getIdentifier("recyclerView", "id", root.context.packageName)
            if (id == 0) return
            val refreshLayout = root.findViewById<View>(id) ?: return
            // setLoadMoreComplete(true) fires notify -> clears the spinner + marks "no more";
            // setLoadMoreEnable(false) removes the footer and blocks further scroll-triggered loads.
            FeatureHooks.invokeOrNull(
                publicMethod(refreshLayout, "setLoadMoreComplete", parameterCount = 1),
                refreshLayout,
                true,
            )
            FeatureHooks.invokeOrNull(
                publicMethod(refreshLayout, "setLoadMoreEnable", parameterCount = 1),
                refreshLayout,
                false,
            )
        }.onFailure { throwable ->
            FeatureHooks.w("Selection stop-load-more failed: ${throwable.javaClass.name}")
        }
    }

    fun scanItemsFromCards(cards: List<Any?>): List<PageCustomItem> {
        val items = linkedMapOf<String, PageCustomItem>()
        val limited = cards.take(MAX_SCAN_CARDS)
        for (card in limited) {
            if (isDisabledGuessSectionCard(card)) {
                break
            }
            itemsForCard(card).forEach { item ->
                items.putIfAbsent(item.key, item)
            }
        }
        return items.values.toList()
    }

    fun snapshotCardsFromSource(source: Any?): List<Any?> {
        return cardsFromSource(source)
            .take(MAX_SCAN_CARDS)
            .toList()
    }

    fun snapshotCardsFromRoot(root: View): List<Any?> {
        val cards = ArrayList<Any?>(MAX_SCAN_CARDS)
        traverse(root) { view ->
            if (cards.size >= MAX_SCAN_CARDS) return@traverse
            val adapter = adapterOf(view) ?: return@traverse
            if (!isSelectionAdapter(adapter)) return@traverse
            cardsFromSource(invokeNoArg(adapter, "getAllItems")).forEach { card ->
                if (cards.size < MAX_SCAN_CARDS) {
                    cards += card
                }
            }
        }
        return cards
    }

    fun isFlutterSelectionPath(path: String): Boolean {
        val rawPath = path.substringBefore('?').trimEnd('/').lowercase()
        val argusIndex = rawPath.indexOf("/argus/")
        val normalized = if (argusIndex >= 0) rawPath.substring(argusIndex) else rawPath
        return normalized == FLUTTER_SELECTION_PATH
    }

    /** The same endpoint also serves category/detail pages. Missing scope is not selection. */
    fun isFlutterSelectionRequest(request: NativeNetRequest): Boolean {
        if (request.ownerClassName != QDReaderHost.Classes.MAIN_GROUP_ACTIVITY ||
            !isFlutterSelectionPath(request.path)) return false
        val parameters = request.parameters
        return parameters["site"]?.toIntOrNull() in selectionSites &&
            parameters["pageId"]?.toLongOrNull() == 0L &&
            parameters["scene"]?.toIntOrNull() == 0 &&
            (parameters["pg"]?.toIntOrNull() ?: 0) >= 1
    }

    fun traceFlutterScope(context: Context?, request: NativeNetRequest, selected: Boolean) {
        if (!SettingsRuntime.isSelectionPageCustomEnabled(context) || reportedFlutterScopes.size >= 12) return
        val params = request.parameters
        val scope = "owner=${request.ownerClassName?.substringAfterLast('.') ?: "unknown"}, " +
            "site=${params["site"]?.take(20)}, pageId=${params["pageId"]?.take(20)}, " +
            "scene=${params["scene"]?.take(20)}, selection=$selected"
        if (reportedFlutterScopes.add(scope)) FeatureHooks.i("Selection Flutter scope: $scope")
    }

    fun rememberFlutterSelectionPayload(raw: String) {
        // Keep only a bounded copy for the settings-side scan. The current payload is normally
        // ~100–150 KiB; unusually large responses should not become a process-wide cache.
        latestFlutterSelectionPayload = raw.takeIf { it.length <= 512_000 }
    }

    fun latestFlutterSelectionPayload(): String? = latestFlutterSelectionPayload

    fun clearFlutterSelectionPayload() {
        latestFlutterSelectionPayload = null
        reportedFlutterScopes.clear()
        reportedFlutterFilters.clear()
    }

    /** Extract the same user-facing section candidates from the Flutter page JSON as the native path. */
    fun scanItemsFromJson(raw: String): List<PageCustomItem> {
        val items = jsonItems(raw) ?: return emptyList()
        val result = linkedMapOf<String, PageCustomItem>()
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            if (isJsonGuessSection(item)) break
            jsonItemsForSection(item).forEach { candidate -> result.putIfAbsent(candidate.key, candidate) }
        }
        return result.values.toList()
    }

    /** Every page is scoped by its own request, including headerless load-more responses. */
    fun filterJson(context: Context?, request: NativeNetRequest, raw: String): String {
        context ?: return raw
        if (!isFlutterSelectionRequest(request)) return raw
        val parameters = request.parameters
        val customEnabled = SettingsRuntime.isSelectionPageCustomEnabled(context)
        val hideGuess = SettingsRuntime.isSelectionGuessWaterfallHidden(context)
        if (!customEnabled || (!hideGuess && hiddenSelection(context).isEmpty())) return raw
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.first() != '{') return raw
        return runCatching {
            val root = JSONObject(raw)
            val items = jsonItems(root) ?: return@runCatching raw
            val originalCount = items.length()
            var changed = false
            if (hideGuess) {
                val guessIndex = (0 until items.length())
                    .firstOrNull { isJsonGuessSection(items.optJSONObject(it)) }
                    ?: -1
                if (guessIndex >= 0) {
                    for (index in items.length() - 1 downTo guessIndex) items.remove(index)
                    changed = true
                } else if ((parameters["pg"]?.toIntOrNull() ?: 0) > 1 && isGuessContinuation(items)) {
                    // Subsequent Flutter pages omit the 猜你喜欢 header and contain only its
                    // book cards. Keep the response shape but remove that page's items too.
                    while (items.length() > 0) items.remove(items.length() - 1)
                    changed = true
                }
            }
            val hidden = hiddenSelection(context)
            if (!hidden.isEmpty()) {
                for (index in items.length() - 1 downTo 0) {
                    val item = items.optJSONObject(index) ?: continue
                    val data = item.optJSONObject("Data") ?: continue
                    val multiList = data.optJSONArray("MultiList")
                    val childCandidates = multiList?.let { jsonChildCandidates(item, data, it) }.orEmpty()
                    if (childCandidates.isNotEmpty()) {
                        val next = JSONArray()
                        for (childIndex in 0 until multiList!!.length()) {
                            val child = multiList.optJSONObject(childIndex) ?: continue
                            val candidate = childCandidates.firstOrNull { it.first === child }?.second
                            if (candidate == null || !hidden.matches(candidate)) next.put(child)
                            else changed = true
                        }
                        if (next.length() == 0) {
                            items.remove(index)
                            changed = true
                        } else if (next.length() != multiList.length()) {
                            data.put("MultiList", next)
                        }
                    } else {
                        val candidate = jsonItemsForSection(item).firstOrNull()
                        if (candidate != null && hidden.matches(candidate)) {
                            items.remove(index)
                            changed = true
                        }
                    }
                }
            }
            if (changed) {
                val pageKind = if (parameters["pg"]?.toIntOrNull() == 1) "initial" else "continuation"
                val key = "${parameters["site"]}/$pageKind"
                if (reportedFlutterFilters.size < 12 && reportedFlutterFilters.add(key)) {
                    FeatureHooks.i("Selection Flutter filtered: site=${parameters["site"]}, page=$pageKind, " +
                        "hideGuess=$hideGuess, items=$originalCount->${items.length()}")
                }
                root.toString()
            } else raw
        }.getOrDefault(raw)
    }

    fun processAdapterList(
        context: Context?,
        adapter: Any?,
        source: Any?,
        isAppend: Boolean = false,
        onScanResult: ((PageCustomScanResult) -> Unit)? = null,
    ): AdapterProcessResult {
        if (!isSelectionAdapter(adapter)) {
            return AdapterProcessResult(null, changed = false)
        }
        val hostContext = context ?: contextFromAdapter(adapter) ?: HostSessionStore.contextOrNull()
        PageCustomization.consumePendingSelectionScanFromSourceAsync(
            hostContext,
            source,
        ) { result ->
            onScanResult?.invoke(result)
        }
        val replacement = filterSource(hostContext, source, isAppend)
        return AdapterProcessResult(
            replacementList = replacement,
            changed = replacement != null,
        )
    }

    private fun filterSource(context: Context?, source: Any?, isAppend: Boolean): List<Any?>? {
        context ?: return null
        val customEnabled = SettingsRuntime.isSelectionPageCustomEnabled(context)
        if (!customEnabled) return null
        // hideGuess is itself gated by the master switch above, so it only applies under it.
        val hideGuess = SettingsRuntime.isSelectionGuessWaterfallHidden(context)
        val cards = source as? List<*> ?: return null

        var working: List<*> = cards
        var changed = false

        // (1) Shield the whole 猜你喜欢 waterfall: drop the first guess card and everything after it.
        // This runs on updateList (initial tail) AND appendList/addItems (each infinite page).
        if (hideGuess) {
            val guessIndex = cards.indexOfFirst { isDisabledGuessSectionCard(it) }
            // Replace ops must keep >=1 card (guessIndex>0) or DiffUtil would blank the page; append ops
            // may truncate to empty (that page was pure guess). Load-more is stopped separately.
            val canTruncate = if (isAppend) guessIndex >= 0 else guessIndex > 0
            if (canTruncate) {
                working = cards.take(guessIndex)
                changed = true
                // Fast path: an all-guess append page needs no per-item work — skip the hidden JSON parse.
                if (isAppend) return working
            }
        }

        // (2) Per-item hiding from the customizable selection list (only when that feature is on).
        if (!customEnabled) {
            return working.takeIf { changed }
        }
        val hidden = hiddenSelection(context)
        if (hidden.isEmpty()) {
            return working.takeIf { changed }
        }
        val filtered = ArrayList<Any?>(working.size)
        working.forEach { card ->
            if (!isMorphingCard(card)) {
                filtered += card
                return@forEach
            }
            val item = wholeCardItem(card)
            if (item != null && hidden.matches(item)) {
                changed = true
                return@forEach
            }
            val next = filterCard(card, hidden)
            if (next == null) {
                changed = true
            } else {
                if (next !== card) changed = true
                filtered += next
            }
        }
        return filtered.takeIf { changed }
    }

    private fun hiddenSelection(context: Context): HiddenSelection {
        val keys = SettingsRuntime.prefs(context)
            .getString(SettingIds.KEY_SELECTION_PAGE_CUSTOM_HIDDEN, null)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            .orEmpty()
        if (keys.isEmpty()) {
            return HiddenSelection(emptySet(), emptySet())
        }
        val storedByKey = PageCustomization.storedItems(context, PageCustomKind.Selection)
            .associateBy { item -> item.key }
        val legacyLabels = keys
            .mapNotNull { key -> storedByKey[key]?.label }
            .toSet()
        return HiddenSelection(keys, legacyLabels)
    }

    private fun filterCard(card: Any?, hidden: HiddenSelection): Any? {
        val targetCard = card ?: return null
        val viewType = intValue(targetCard, "getViewType") ?: return targetCard
        if (viewType != ENTRANCE_VIEW_TYPE) {
            return card
        }
        val data = invokeNoArg(targetCard, "getData") ?: return targetCard
        val multiData = invokeNoArg(data, "getMultiData") as? List<*> ?: return targetCard
        if (multiData.isEmpty()) return targetCard

        var changed = false
        val nextMultiData = ArrayList<Any?>(multiData.size)
        multiData.forEach { child ->
            val childType = intValue(child, "getType")
            val childKey = childType?.let { keyForChild(targetCard, child, it) }
            val childLabel = childType?.let(::labelForEntranceChildType)
            val childItem = if (childKey != null && childLabel != null) {
                PageCustomItem(childKey, childLabel)
            } else {
                null
            }
            if (childItem != null && hidden.matches(childItem)) {
                changed = true
            } else {
                nextMultiData += child
            }
        }
        if (!changed) return targetCard
        if (nextMultiData.isEmpty()) return null
        val copiedData = copyWidgetDataWithMultiData(data, nextMultiData) ?: return targetCard
        return copyCardWithData(targetCard, copiedData) ?: targetCard
    }

    private fun itemsForCard(card: Any?): List<PageCustomItem> {
        if (!isMorphingCard(card)) return emptyList()
        val viewType = intValue(card, "getViewType") ?: return emptyList()
        val data = invokeNoArg(card, "getData")
        if (viewType == ENTRANCE_VIEW_TYPE) {
            val multiData = invokeNoArg(data, "getMultiData") as? List<*> ?: return emptyList()
            return multiData.mapNotNull { child ->
                val childType = intValue(child, "getType") ?: return@mapNotNull null
                val label = when (childType) {
                    ENTRANCE_GRID_TYPE -> "入口宫格"
                    ENTRANCE_TICKER_TYPE -> "轮播消息"
                    else -> null
                } ?: return@mapNotNull null
                PageCustomItem(keyForChild(card, child, childType), label)
            }
        }

        return wholeCardItem(card)?.let(::listOf).orEmpty()
    }

    private fun wholeCardItem(card: Any?): PageCustomItem? {
        val viewType = intValue(card, "getViewType") ?: return null
        val data = invokeNoArg(card, "getData")
        if (isDisabledGuessSectionData(data)) return null
        val label = when {
            viewType == 1 -> "顶部轮播图"
            else -> primaryCardLabel(data) ?: modelLabelForViewType(viewType)
        } ?: return null
        return PageCustomItem(keyForCard(card, label), label)
    }

    private fun labelForEntranceChildType(childType: Int): String? {
        return when (childType) {
            ENTRANCE_GRID_TYPE -> "入口宫格"
            ENTRANCE_TICKER_TYPE -> "轮播消息"
            else -> null
        }
    }

    private fun primaryCardLabel(data: Any?): String? {
        data ?: return null
        val title = invokeNoArg(data, "getCardTitle")
        readableLabel(stringValue(title, "getName"))?.let { return it }
        readableLabel(stringValue(invokeNoArg(data, "getExtension"), "getColumnName"))?.let { return it }
        readableLabel(stringValue(data, "getSelectName"))?.let { return it }
        return null
    }

    private fun readableLabel(value: String?): String? {
        val label = value
            ?.replace(Regex("\\s+"), "")
            ?.trim()
            .orEmpty()
        if (label.isBlank() || label.length > 18) return null
        if (label.contains("://") || label.contains('/') || label.contains('\\')) return null
        if (isDisabledGuessSectionValue(label)) return null
        return label
    }

    private fun isDisabledGuessSectionData(data: Any?): Boolean {
        data ?: return false
        val extension = invokeNoArg(data, "getExtension")
        // pageDataType == "yuedupianhao" is the stable model token (server-supplied); the strongest,
        // most drift-resistant anchor — check it first and short-circuit.
        if (isDisabledGuessSectionValue(stringValue(extension, "getPageDataType"))) {
            return true
        }
        val title = invokeNoArg(data, "getCardTitle")
        return listOf(
            stringValue(extension, "getColumnName"),
            stringValue(extension, "getDataType"),
            stringValue(title, "getName"),
            stringValue(data, "getSelectName"),
            stringValue(data, "getListType"),
        ).any(::isDisabledGuessSectionValue)
    }

    private fun isDisabledGuessSectionCard(card: Any?): Boolean {
        if (!isMorphingCard(card)) return false
        return isDisabledGuessSectionData(invokeNoArg(card, "getData"))
    }

    private fun jsonItems(raw: String): JSONArray? = runCatching {
        val root = JSONObject(raw)
        jsonItems(root)
    }.getOrNull()

    private fun jsonItems(root: JSONObject): JSONArray? {
        return root.optJSONObject("Data")?.optJSONArray("Items")
            ?: root.optJSONArray("Items")
    }

    private fun jsonItemsForSection(item: JSONObject): List<PageCustomItem> {
        val data = item.optJSONObject("Data") ?: return emptyList()
        val children = data.optJSONArray("MultiList")?.let { jsonChildCandidates(item, data, it) }.orEmpty()
        if (children.isNotEmpty()) return children.map { it.second }
        val label = jsonSectionLabel(data) ?: return emptyList()
        return listOf(PageCustomItem(jsonCardKey(item, data, label), label))
    }

    private fun jsonChildCandidates(
        item: JSONObject,
        data: JSONObject,
        array: JSONArray,
    ): List<Pair<JSONObject, PageCustomItem>> {
        val result = ArrayList<Pair<JSONObject, PageCustomItem>>()
        val common = data.optJSONObject("CommonExt")
        val parent = listOf(
            "view=${item.optInt("Type", data.optInt("Type", 0))}",
            "column=${common?.optString("ColumnName").orEmpty()}",
            "pdt=${common?.optString("PageDataType").orEmpty()}",
            "listType=${data.optString("ListType")}",
            "itemStyle=${data.optInt("ItemStyle", 0)}",
        )
        for (index in 0 until array.length()) {
            val child = array.optJSONObject(index) ?: continue
            val type = child.optInt("Type", 0)
            val label = labelForJsonChildType(type) ?: continue
            result += child to PageCustomItem(
                key = "selection:child:${digest(parent + listOf("childType=$type", "childLabel=$label"))}",
                label = label,
            )
        }
        return result
    }

    private fun jsonSectionLabel(data: JSONObject): String? {
        val title = data.optJSONObject("Title")
        val column = data.optJSONObject("CommonExt")?.optString("ColumnName")
        val normalizedColumn = column?.trim()?.uppercase()
        val normalizedLabel = when (normalizedColumn) {
            "BANNER" -> "顶部轮播图"
            "APP_TAG_RANK" -> "排行榜"
            "APP_TAG" -> "分类推荐"
            else -> null
        }
        return normalizedLabel
            ?: readableLabel(title?.optString("Name"))
            ?: readableLabel(column)
            ?: readableLabel(data.optString("SelectName"))
    }

    private fun labelForJsonChildType(type: Int): String? {
        return when (type) {
            113 -> "入口宫格"
            114 -> "轮播消息"
            115 -> "顶部轮播图"
            112 -> "排行榜"
            107 -> "分类推荐"
            else -> null
        }
    }

    private fun isGuessContinuation(items: JSONArray): Boolean {
        var hasBookList = false
        var hasAnchor = false
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val data = item.optJSONObject("Data") ?: continue
            if (data.optJSONArray("BookList")?.length()?.let { it > 0 } == true) hasBookList = true
            val labels = jsonItemsForSection(item).map(PageCustomItem::label)
            if (labels.any { it in setOf("入口宫格", "轮播消息", "顶部轮播图", "排行榜", "分类推荐") }) {
                hasAnchor = true
            }
        }
        return hasBookList && !hasAnchor
    }

    private fun isJsonGuessSection(item: JSONObject?): Boolean {
        val data = item?.optJSONObject("Data") ?: return false
        val common = data.optJSONObject("CommonExt")
        return listOf(
            common?.optString("ColumnName"),
            common?.optString("PageDataType"),
            data.optJSONObject("Title")?.optString("Name"),
            data.optString("SelectName"),
            data.optString("ListType"),
        ).any(::isDisabledGuessSectionValue)
    }

    private fun jsonCardKey(item: JSONObject, data: JSONObject, label: String): String {
        val common = data.optJSONObject("CommonExt")
        return "selection:card:${digest(listOf(
            "view=${item.optInt("Type", data.optInt("Type", 0))}",
            "child=0",
            "label=$label",
            "column=${common?.optString("ColumnName").orEmpty()}",
            "pdt=${common?.optString("PageDataType").orEmpty()}",
            "listType=${data.optString("ListType")}",
            "itemStyle=${data.optInt("ItemStyle", 0)}",
        ))}"
    }

    private fun isDisabledGuessSectionValue(value: String?): Boolean {
        val normalized = value
            ?.replace(Regex("\\s+"), "")
            ?.trim()
            ?.lowercase()
            .orEmpty()
        return normalized in disabledGuessSectionTokens
    }

    private fun modelLabelForViewType(viewType: Int): String? {
        return when (viewType) {
            in rankViewTypes -> "排行榜"
            2, 108 -> "推荐轮播"
            else -> null
        }
    }

    private fun keyForCard(card: Any?, label: String): String {
        return "selection:card:${digest(structuralParts(card, childType = null, label = label))}"
    }

    private fun keyForChild(card: Any?, child: Any?, childType: Int): String {
        val childParts = listOf(
            "childType=$childType",
            "childLabel=${labelForEntranceChildType(childType).orEmpty()}",
        )
        return "selection:child:${digest(structuralParts(card, childType, label = null) + childParts)}"
    }

    private fun structuralParts(card: Any?, childType: Int?, label: String?): List<String> {
        val data = invokeNoArg(card, "getData")
        val extension = invokeNoArg(data, "getExtension")
        val viewType = intValue(card, "getViewType") ?: 0
        val normalizedLabel = label.orEmpty()
        return buildList {
            add("view=$viewType")
            add("child=${childType ?: 0}")
            add("label=$normalizedLabel")
            add("column=${stringValue(extension, "getColumnName").orEmpty()}")
            add("pdt=${stringValue(extension, "getPageDataType").orEmpty()}")
            add("listType=${stringValue(data, "getListType").orEmpty()}")
            add("itemStyle=${intValue(data, "getItemStyle") ?: 0}")
        }
    }

    private fun cardsFromSource(source: Any?): List<Any?> {
        if (source == null) return emptyList()
        if (isSelectionAdapter(source)) {
            return cardsFromSource(invokeNoArg(source, "getAllItems"))
        }
        if (source !is Iterable<*>) return emptyList()
        return source.asSequence()
            .filter { item -> isMorphingCard(item) }
            .take(MAX_SCAN_CARDS)
            .toList()
    }

    private fun isSelectionAdapter(adapter: Any?): Boolean {
        adapter ?: return false
        if (adapter.javaClass.name != MORPHING_ADAPTER) return false
        return invokeNoArg(adapter, "isFromMorphingFragment") == true
    }

    private fun isMorphingCard(value: Any?): Boolean {
        return value?.javaClass?.name == MORPHING_CARD
    }

    private fun copyCardWithData(card: Any, data: Any): Any? {
        val method = publicMethod(card, "copy", parameterCount = 6) ?: return null
        return runCatching {
            FeatureHooks.invokeOrNull(
                method,
                card,
                intValue(card, "getViewType") ?: 0,
                invokeNoArg(card, "getAttr"),
                data,
                intValue(card, "getGroupId") ?: -1,
                intValue(card, "getTrackViewType") ?: 0,
                intValue(card, "getTabIndex") ?: 0,
            )
        }.onFailure { throwable ->
            FeatureHooks.w("Selection card copy failed: ${throwable.javaClass.name}")
        }.getOrNull()
    }

    private fun copyWidgetDataWithMultiData(data: Any, multiData: List<Any?>): Any? {
        val method = publicMethod(data, "copy", parameterCount = 20) ?: return null
        return runCatching {
            val list = invokeNoArg(data, "getList") as? List<*> ?: emptyList<Any?>()
            FeatureHooks.invokeOrNull(
                method,
                data,
                invokeNoArg(data, "getCardTitle"),
                intValue(data, "getType") ?: 0,
                stringValue(data, "getSelectId"),
                stringValue(data, "getSelectName"),
                stringValue(data, "getSelectIcon"),
                stringValue(data, "getSelectTip"),
                intValue(data, "getPosition") ?: 0,
                invokeNoArg(data, "getExtension"),
                list,
                multiData,
                intValue(data, "getNodeId") ?: 0,
                invokeNoArg(data, "getTabs"),
                stringValue(data, "getListType"),
                invokeNoArg(data, "getBookList"),
                invokeNoArg(data, "getImageTextList"),
                invokeNoArg(data, "getAudioList"),
                intValue(data, "getInnerIndex") ?: -1,
                invokeNoArg(data, "getOtherMap"),
                intValue(data, "getShowOrder") ?: 0,
                intValue(data, "getItemStyle") ?: 0,
            )
        }.onFailure { throwable ->
            FeatureHooks.w("Selection widget data copy failed: ${throwable.javaClass.name}")
        }.getOrNull()
    }

    private fun adapterOf(view: View): Any? {
        return FeatureHooks.invokeNoArgOrNull(view, "getAdapter")
    }

    private fun contextFromAdapter(adapter: Any?): Context? {
        adapter ?: return null
        var clazz: Class<*>? = adapter.javaClass
        while (clazz != null) {
            val field = FeatureHooks.findFieldOrNull(clazz, "ctx")
            if (field != null) return FeatureHooks.read(field, adapter) as? Context
            clazz = clazz.superclass
        }
        return null
    }

    private fun publicMethod(source: Any?, name: String, parameterCount: Int): Method? {
        source ?: return null
        return FeatureHooks.publicMethodOrNull(source.javaClass, name, parameterCount)
    }

    private fun invokeNoArg(source: Any?, methodName: String): Any? {
        return FeatureHooks.invokeNoArgOrNull(source, methodName)
    }

    private fun intValue(source: Any?, methodName: String): Int? {
        return invokeNoArg(source, methodName) as? Int
    }

    private fun stringValue(source: Any?, methodName: String): String? {
        return invokeNoArg(source, methodName) as? String
    }

    private fun digest(parts: List<String>): String {
        val bytes = MessageDigest.getInstance("SHA-1")
            .digest(parts.joinToString("|").toByteArray(Charsets.UTF_8))
        return bytes.take(10).joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun traverse(root: View, visitor: (View) -> Unit) {
        visitor(root)
        if (root !is ViewGroup) return
        for (index in 0 until root.childCount) {
            traverse(root.getChildAt(index), visitor)
        }
    }
}
