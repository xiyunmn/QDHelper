package com.xiyunmn.qdhelper.feature.bookshield.hook

import com.xiyunmn.qdhelper.feature.bookshield.policy.BookShieldJsonFilter
import com.xiyunmn.qdhelper.feature.bookshield.policy.BookShieldLocations
import com.xiyunmn.qdhelper.feature.bookshield.policy.BookShieldRules
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.feature.api.runtime.NativeNetBridgeRuntime
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import java.util.concurrent.atomic.AtomicLong

internal object BookStoreFlutterShield {
    private val hitCount = AtomicLong(0L)

    fun install(module: HookModule, classLoader: ClassLoader, phase: String): Int {
        NativeNetBridgeRuntime.register("book-store-shield", priority = 50) { path, response ->
            filterValue(response, path)
        }
        return NativeNetBridgeRuntime.install(module, classLoader, phase)
    }

    internal fun filterValue(
        raw: String,
        path: String,
        location: String? = flutterBookShieldLocation(path),
    ): String {
        location ?: return raw
        val context = HostSessionStore.contextOrNull()
        val trace = SettingsRuntime.isShieldTraceEnabled(context)
        if (trace && looksLikeBookPayload(raw)) {
            FeatureHooks.i(
                "Shield trace flutter: location=$location, path=${safePath(path)}, len=${raw.length}, booky=true",
            )
        }
        if (!SettingsRuntime.isBookShieldLocationEnabled(context, location)) {
            return raw
        }
        val rules = BookShieldRules.from(context)
        if (!rules.hasRules) return raw
        val result = BookShieldJsonFilter.filter(raw, rules, location)
        if (result.removed > 0) {
            val count = hitCount.incrementAndGet()
            if (count <= 30L || count % 50L == 0L) {
                FeatureHooks.i(
                    "Book shield flutter filtered#$count: location=$location, " +
                        "path=${safePath(path)}, removed=${result.removed}",
                )
            }
        }
        return result.json
    }

    private fun looksLikeBookPayload(raw: String): Boolean =
        raw.contains("BookName") || raw.contains("bookName") ||
            raw.contains("ItemName") || raw.contains("itemName") ||
            raw.contains("AuthorName") || raw.contains("BookId") || raw.contains("bookId") ||
            raw.contains("\"CBID\"") || raw.contains("\"CbId\"") || raw.contains("\"cbId\"")

    private fun safePath(path: String?): String =
        path?.take(96)?.replace(Regex("[^A-Za-z0-9_./?-]"), "_") ?: "-"
}

internal fun flutterBookShieldLocation(path: String): String? {
    val rawPath = path.substringBefore('?').trimEnd('/').lowercase()
    val argusIndex = rawPath.indexOf("/argus/")
    val normalized = if (argusIndex >= 0) rawPath.substring(argusIndex) else rawPath
    return when {
        normalized == DAILY_READING_RECOMMEND_PATH ->
            BookShieldLocations.DAILY_READING_FLUTTER
        normalized in BOOKSTORE_BOOK_PATHS ->
            BookShieldLocations.FLUTTER_BOOK_STREAM
        else -> null
    }
}

private const val DAILY_READING_RECOMMEND_PATH = "/argus/api/v2/dailyrecommend/getdailyrecommend"
private val BOOKSTORE_BOOK_PATHS = setOf(
    "/argus/api/v1/bookstore/getbooks",
    "/argus/api/v1/booksquare/getcolumndiscountbooks",
    "/argus/api/v1/booksquare/getsjrecommend",
    "/argus/api/v1/booksquare/getsquarenodepage",
    "/argus/api/v1/booksquare/getsquarepagepiece",
    "/argus/api/v1/booksquare/morepagetab",
    "/argus/api/v1/booksquare/newbookmorepage",
    "/argus/api/v1/square/morepage",
    "/argus/api/v2/booksquare/newbookpage",
)
