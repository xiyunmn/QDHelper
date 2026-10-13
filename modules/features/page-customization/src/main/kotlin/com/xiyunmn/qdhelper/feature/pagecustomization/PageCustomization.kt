package com.xiyunmn.qdhelper.feature.pagecustomization

import android.app.Activity
import android.content.Context
import android.view.View
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureResources
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols as QDReaderHost
import com.xiyunmn.qdhelper.hook.concurrency.ManagedExecutor
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.config.SettingIds
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

enum class PageCustomKind(
    val id: String,
    val label: String,
    val enabledKey: String,
    val entriesKey: String,
    val hiddenKey: String,
) {
    Mine(
        id = "mine",
        label = "我的",
        enabledKey = SettingIds.KEY_MINE_PAGE_CUSTOM_ENABLED,
        entriesKey = SettingIds.KEY_MINE_PAGE_CUSTOM_ENTRIES,
        hiddenKey = SettingIds.KEY_MINE_PAGE_CUSTOM_HIDDEN,
    ),
    Selection(
        id = "selection",
        label = "精选",
        enabledKey = SettingIds.KEY_SELECTION_PAGE_CUSTOM_ENABLED,
        entriesKey = SettingIds.KEY_SELECTION_PAGE_CUSTOM_ENTRIES,
        hiddenKey = SettingIds.KEY_SELECTION_PAGE_CUSTOM_HIDDEN,
    ),
    BookDetail(
        id = "book_detail",
        label = "书籍详情页",
        enabledKey = SettingIds.KEY_BOOK_DETAIL_PAGE_CUSTOM_ENABLED,
        entriesKey = SettingIds.KEY_BOOK_DETAIL_PAGE_CUSTOM_ENTRIES,
        hiddenKey = SettingIds.KEY_BOOK_DETAIL_PAGE_CUSTOM_HIDDEN,
    ),
}

data class PageCustomItem(
    val key: String,
    val label: String,
)

data class PageCustomScanResult(
    val matchedPage: Boolean,
    val count: Int,
)

object PageCustomization {
    private const val MAIN_GROUP_ACTIVITY = QDReaderHost.Classes.MAIN_GROUP_ACTIVITY
    private const val MAIN_TAB_SELECTION = 1
    private const val MAIN_TAB_MINE = 3
    private const val PENDING_SCAN_TTL_MS = 60_000L
    @Volatile private var pendingScanKindInMemory: PageCustomKind? = null
    @Volatile private var pendingScanExpiresAt: Long = 0L
    private val pendingScanRunning = AtomicBoolean(false)
    private val scanResultListeners = CopyOnWriteArraySet<(PageCustomKind, PageCustomScanResult) -> Unit>()
    private val scanExecutor = ManagedExecutor(
        ownerKey = "page-customization-selection-scan",
        threadName = "QDHelper-selection-scan",
        onFailure = { throwable -> FeatureHooks.e("Page customization scan failed", throwable) },
    )
    init { FeatureResources.register("PageCustomization") { clearHotReloadState() } }
    fun storedItems(context: Context, kind: PageCustomKind): List<PageCustomItem> {
        val raw = SettingsRuntime.prefs(context).getString(kind.entriesKey, "") ?: ""
        if (raw.isBlank()) {
            return if (kind == PageCustomKind.BookDetail) BookDetailPageCustomization.knownItems else emptyList()
        }
        if (kind == PageCustomKind.BookDetail) {
            return bookDetailItemsToStore(context)
        }
        val parsed = runCatching {
            val array = JSONArray(raw)
            val items = buildList {
                for (index in 0 until array.length()) {
                    val obj = array.optJSONObject(index) ?: continue
                    val key = obj.optString("key").takeIf { it.isNotBlank() } ?: continue
                    val label = obj.optString("label").takeIf { it.isNotBlank() } ?: continue
                    add(PageCustomItem(key, label))
                }
            }
            uniqueItemsByKey(items, "stored:${kind.id}")
                .filterNot { item ->
                    kind == PageCustomKind.Selection && SelectionPageCustomization.isDisabledItem(item)
                }
        }.getOrDefault(emptyList())
        return parsed
    }

    fun refreshKnownItems(context: Context, kind: PageCustomKind): PageCustomScanResult {
        val count = when (kind) {
            PageCustomKind.BookDetail -> storeItems(context, kind, BookDetailPageCustomization.knownItems)
            PageCustomKind.Mine,
            PageCustomKind.Selection -> 0
        }
        return PageCustomScanResult(matchedPage = count > 0, count = count)
            .also { result -> if (count > 0) notifyScanResult(kind, result) }
    }

    fun addScanResultListener(listener: (PageCustomKind, PageCustomScanResult) -> Unit): AutoCloseable {
        scanResultListeners += listener
        return AutoCloseable { scanResultListeners -= listener }
    }

    fun clearPage(context: Context, kind: PageCustomKind): Boolean {
        clearPendingScan(context, kind)
        return SettingsRuntime.prefs(context).edit()
            .putBoolean(kind.enabledKey, false)
            .putString(kind.entriesKey, "")
            .putString(kind.hiddenKey, "")
            .commit()
    }

    private fun scanAndStoreMine(context: Context, root: View): PageCustomScanResult {
        if (!PageCustomizationViewInspector.isMinePage(root)) {
            return PageCustomScanResult(matchedPage = false, count = 0)
        }
        val scanned = MinePageDataEngine.scanItemsFromRoot(root).ifEmpty { MinePageDataEngine.knownItems() }
        if (scanned.isEmpty()) {
            return PageCustomScanResult(matchedPage = true, count = 0)
        }
        storeItems(context, PageCustomKind.Mine, scanned)
        return PageCustomScanResult(matchedPage = true, count = scanned.size)
    }

    fun consumePendingMineScanFromSource(context: Context?, source: Any?): PageCustomScanResult? {
        context ?: return null
        if (pendingScanKind(context) != PageCustomKind.Mine) return null
        val scanned = MinePageDataEngine.scanItemsFromSource(source)
        if (scanned.isEmpty()) return null
        val after = storeItems(context, PageCustomKind.Mine, scanned)
        clearPendingScanState(context)
        return PageCustomScanResult(matchedPage = true, count = after)
            .also { notifyScanResult(PageCustomKind.Mine, it) }
    }

    fun consumePendingSelectionScanFromSourceAsync(
        context: Context?,
        source: Any?,
        onResult: (PageCustomScanResult) -> Unit,
    ): Boolean {
        context ?: return false
        if (pendingScanKind(context) != PageCustomKind.Selection) return false
        val cards = SelectionPageCustomization.snapshotCardsFromSource(source)
        if (cards.isEmpty()) return false
        return consumePendingSelectionScanAsync(context, cards) { result ->
            onResult(result)
        }
    }

    fun filterMineSource(context: Context?, source: Any?): Boolean {
        context ?: return false
        if (!SettingsRuntime.isMinePageCustomEnabled(context)) return false
        val hiddenLabels = MinePageDataEngine.hiddenLabels(context)
        if (hiddenLabels.isEmpty()) return false
        return MinePageDataEngine.filterData(source, hiddenLabels)
    }

    fun filterMinePageResult(context: Context?, source: Any?): Any? {
        context ?: return source
        if (!SettingsRuntime.isMinePageCustomEnabled(context)) return source
        val hiddenLabels = MinePageDataEngine.hiddenLabels(context)
        if (hiddenLabels.isEmpty()) return source
        MinePageDataEngine.filterData(source, hiddenLabels)
        if (source is List<*>) {
            val filtered = source.filterNot { item ->
                MinePageDataEngine.mineLabelOf(item)?.let(hiddenLabels::contains) == true
            }
            if (filtered.size != source.size) {
                return ArrayList(filtered)
            }
        }
        return source
    }

    fun requestPendingScan(context: Context, kind: PageCustomKind): Boolean {
        if (pendingScanKind(context) != null) {
            return false
        }
        val now = System.currentTimeMillis()
        val expiresAt = now + PENDING_SCAN_TTL_MS
        pendingScanRunning.set(false)
        pendingScanKindInMemory = kind
        pendingScanExpiresAt = expiresAt
        SettingsRuntime.putString(context, SettingIds.KEY_PAGE_CUSTOM_PENDING_SCAN, "${kind.id}:$expiresAt")
        return true
    }

    fun cancelPendingScan(context: Context, kind: PageCustomKind) {
        clearPendingScan(context, kind)
    }

    fun openHostPageForPendingScan(activity: Activity, kind: PageCustomKind): AutoCloseable? {
        if (activity.javaClass.name != MAIN_GROUP_ACTIVITY) {
            return null
        }
        val adapter = PageCustomizationHostAccess.invokeNoArgValue(activity, "getTabsPagerAdapter") ?: return null
        val targetType = when (kind) {
            PageCustomKind.Mine -> MAIN_TAB_MINE
            PageCustomKind.Selection -> MAIN_TAB_SELECTION
            PageCustomKind.BookDetail -> return null
        }
        val targetPosition = PageCustomizationHostAccess.invokeIntMethod(adapter, "getTypePosition", targetType)
            ?: targetType
        if (targetPosition < 0) {
            return null
        }
        val previousPosition = currentMainTabPosition(activity) ?: targetPosition
        if (previousPosition != targetPosition && !switchMainTabPosition(activity, targetPosition)) {
            return null
        }
        return AutoCloseable {
            val current = currentMainTabPosition(activity)
            if (previousPosition != targetPosition && current == targetPosition) {
                switchMainTabPosition(activity, previousPosition)
            }
        }
    }

    fun pendingScanKind(context: Context): PageCustomKind? {
        val now = System.currentTimeMillis()
        if (pendingScanKindInMemory == null) {
            restorePendingScan(context, now)
        }
        val kind = pendingScanKindInMemory ?: return null
        if (now > pendingScanExpiresAt) {
            clearPendingScanState(context)
            return null
        }
        return kind
    }

    fun consumePendingScanIfMatches(root: View): PageCustomScanResult? {
        val context = root.context ?: return null
        val kind = pendingScanKind(context) ?: return null
        if (kind != PageCustomKind.Mine) return null
        if (PageCustomizationViewInspector.isModuleSettingsRoot(root) || !PageCustomizationViewInspector.isMinePage(root)) {
            return null
        }
        val result = scanAndStoreMine(context, root)
        if (result.matchedPage && result.count > 0) {
            clearPendingScanState(context)
            notifyScanResult(kind, result)
        }
        return result
    }

    fun consumePendingScanIfMatchesAsync(
        root: View,
        onResult: (PageCustomScanResult?) -> Unit,
    ): Boolean {
        val context = root.context ?: return false
        val kind = pendingScanKind(context) ?: return false
        if (kind == PageCustomKind.BookDetail) {
            onResult(null)
            return false
        }
        if (kind != PageCustomKind.Selection) {
            onResult(consumePendingScanIfMatches(root))
            return false
        }
        if (PageCustomizationViewInspector.isModuleSettingsRoot(root)) return false
        val cards = SelectionPageCustomization.snapshotCardsFromRoot(root)
        if (cards.isEmpty()) return false
        return consumePendingSelectionScanAsync(context, cards) { result ->
            root.post { onResult(result) }
        }
    }

    private fun storeItems(context: Context, kind: PageCustomKind, items: List<PageCustomItem>): Int {
        val savedItems = when (kind) {
            PageCustomKind.Mine -> storedItems(context, kind)
                .plus(items)
                .map { it.label }
                .let(MinePageDataEngine::toItems)
            PageCustomKind.Selection -> selectionItemsToStore(context, items)
            PageCustomKind.BookDetail -> bookDetailItemsToStore(context)
        }
        val array = JSONArray()
        savedItems.forEach { item ->
            array.put(JSONObject().put("key", item.key).put("label", item.label))
        }
        SettingsRuntime.putString(context, kind.entriesKey, array.toString())
        return savedItems.size
    }

    private fun consumePendingSelectionScanAsync(
        context: Context,
        cards: List<Any?>,
        onResult: (PageCustomScanResult) -> Unit,
    ): Boolean {
        if (!pendingScanRunning.compareAndSet(false, true)) return false
        val accepted = scanExecutor.execute {
            try {
                val result = consumePendingSelectionScanSnapshot(context, cards)
                if (result != null) onResult(result)
            } finally {
                pendingScanRunning.set(false)
            }
        }
        if (!accepted) pendingScanRunning.set(false)
        return accepted
    }

    private fun clearHotReloadState() {
        scanResultListeners.clear(); scanExecutor.close()
        pendingScanRunning.set(false); pendingScanKindInMemory = null; pendingScanExpiresAt = 0L
    }
    private fun consumePendingSelectionScanSnapshot(
        context: Context,
        cards: List<Any?>,
    ): PageCustomScanResult? {
        if (pendingScanKind(context) != PageCustomKind.Selection) return null
        val scanned = SelectionPageCustomization.scanItemsFromCards(cards)
        if (scanned.isEmpty()) return null
        val after = storeItems(context, PageCustomKind.Selection, scanned)
        clearPendingScanState(context)
        return PageCustomScanResult(matchedPage = true, count = after)
            .also { notifyScanResult(PageCustomKind.Selection, it) }
    }

    private fun selectionItemsToStore(
        context: Context,
        items: List<PageCustomItem>,
    ): List<PageCustomItem> {
        val previousItemsByKey = storedItems(context, PageCustomKind.Selection).associateBy { it.key }
        val previousHidden = MinePageDataEngine.csvKeys(context, PageCustomKind.Selection.hiddenKey)
        val legacyHiddenLabels = previousHidden
            .mapNotNull { key -> previousItemsByKey[key]?.label }
            .toSet()
        val currentItems = uniqueItemsByKey(
            items.filter { item -> item.key.isNotBlank() && item.label.isNotBlank() },
            "selection-scan",
        )
        if (previousHidden.isNotEmpty()) {
            val nextHidden = currentItems
                .filter { item -> item.key in previousHidden || item.label in legacyHiddenLabels }
                .map { item -> item.key }
                .toSet()
            SettingsRuntime.putString(context, PageCustomKind.Selection.hiddenKey, nextHidden.joinToString(","))
        }
        return currentItems
    }

    private fun uniqueItemsByKey(items: List<PageCustomItem>, source: String): List<PageCustomItem> {
        val seen = HashSet<String>(items.size)
        var duplicateCount = 0
        val unique = buildList(items.size) {
            items.forEach { item ->
                if (seen.add(item.key)) {
                    add(item)
                } else {
                    duplicateCount += 1
                }
            }
        }
        if (duplicateCount > 0) {
            FeatureHooks.w("Page customization ignored duplicate item keys: source=$source, count=$duplicateCount")
        }
        return unique
    }

    private fun bookDetailItemsToStore(context: Context): List<PageCustomItem> {
        val knownItems = BookDetailPageCustomization.knownItems
        val previousHidden = MinePageDataEngine.csvKeys(context, PageCustomKind.BookDetail.hiddenKey)
        if (previousHidden.isNotEmpty()) {
            val nextHidden = knownItems
                .filter { item -> item.key in previousHidden || item.label in previousHidden }
                .map { item -> item.key }
                .toSet()
            SettingsRuntime.putString(context, PageCustomKind.BookDetail.hiddenKey, nextHidden.joinToString(","))
        }
        return knownItems
    }

    private fun restorePendingScan(context: Context, now: Long) {
        val raw = SettingsRuntime.runtimeString(context, SettingIds.KEY_PAGE_CUSTOM_PENDING_SCAN)
        if (raw.isBlank()) return
        val parts = raw.split(':', limit = 2)
        val kind = PageCustomKind.entries.firstOrNull { it.id == parts.getOrNull(0) }
        val expiresAt = parts.getOrNull(1)?.toLongOrNull()
        if (kind == null || expiresAt == null || now > expiresAt) {
            clearPendingScanState(context)
            return
        }
        pendingScanKindInMemory = kind
        pendingScanExpiresAt = expiresAt
    }

    private fun clearPendingScan(context: Context, kind: PageCustomKind) {
        if (pendingScanKind(context) == kind) {
            clearPendingScanState(context)
        }
    }

    private fun clearPendingScanState(context: Context?) {
        pendingScanKindInMemory = null
        pendingScanExpiresAt = 0L
        context ?: return
        SettingsRuntime.putString(context, SettingIds.KEY_PAGE_CUSTOM_PENDING_SCAN, "")
    }

    private fun notifyScanResult(kind: PageCustomKind, result: PageCustomScanResult) {
        scanResultListeners.forEach { listener ->
            runCatching { listener(kind, result) }
        }
    }

    private fun currentMainTabPosition(activity: Activity): Int? {
        val pager = PageCustomizationHostAccess.fieldValue(activity, "pager") ?: return null
        return PageCustomizationHostAccess.invokeIntMethod(pager, "getCurrentItem")
    }

    private fun switchMainTabPosition(activity: Activity, position: Int): Boolean {
        return runCatching {
            val direct = FeatureHooks.declaredMethodsNamed(activity.javaClass, "checkMainGroupPositionTab")
                .singleOrNull { method ->
                    method.parameterTypes.size == 1 &&
                    method.parameterTypes[0] == Integer.TYPE
                }
            if (direct != null) {
                FeatureHooks.invoke(direct, activity, position)
                return@runCatching true
            }
            val tabs = PageCustomizationHostAccess.invokeNoArgValue(activity, "getTabs")
                ?: PageCustomizationHostAccess.fieldValue(activity, "tabs")
            val pager = PageCustomizationHostAccess.fieldValue(activity, "pager") ?: return@runCatching false
            val current = PageCustomizationHostAccess.invokeIntMethod(pager, "getCurrentItem") ?: 0
            // PagerSlidingTabStrip obfuscates its tab-select method to a single letter that can drift
            // across versions. The (int, int, boolean) void signature is
            // unique on the strip, so match it structurally with the letter kept only as a tie-break.
            tabs?.let { FeatureHooks.allDeclaredMethods(it.javaClass) }?.let { candidates ->
                val bySignature = candidates.filter { method ->
                    method.returnType == Void.TYPE &&
                        method.parameterTypes.size == 3 &&
                        method.parameterTypes[0] == Integer.TYPE &&
                        method.parameterTypes[1] == Integer.TYPE &&
                        method.parameterTypes[2] == java.lang.Boolean.TYPE
                }
                bySignature.singleOrNull()
                    ?: bySignature.firstOrNull { method -> method.name == "z" }
            }?.let { method -> FeatureHooks.invoke(method, tabs, position, current, false) }
            val setCurrentItem = FeatureHooks.declaredMethodsNamed(pager.javaClass, "setCurrentItem")
                .singleOrNull { method ->
                    method.parameterTypes.size == 2 &&
                    method.parameterTypes[0] == Integer.TYPE &&
                    method.parameterTypes[1] == java.lang.Boolean.TYPE
                } ?: return@runCatching false
            FeatureHooks.invoke(setCurrentItem, pager, position, false)
            true
        }.getOrDefault(false)
    }

}
