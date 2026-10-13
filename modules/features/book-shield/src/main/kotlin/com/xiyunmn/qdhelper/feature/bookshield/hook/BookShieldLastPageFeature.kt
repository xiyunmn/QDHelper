package com.xiyunmn.qdhelper.feature.bookshield.hook

import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import com.xiyunmn.qdhelper.feature.api.runtime.InstallableFeature
import com.xiyunmn.qdhelper.feature.bookshield.policy.BookListFilter
import com.xiyunmn.qdhelper.feature.bookshield.policy.BookShieldLocations
import com.xiyunmn.qdhelper.feature.bookshield.policy.BookShieldRules
import com.xiyunmn.qdhelper.feature.bookshield.model.BookInfoExtractor
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import java.lang.reflect.Method

object BookShieldLastPageFeature : InstallableFeature {
    override val name: String = "BookShieldLastPageFeature"

    override fun install(
        module: HookModule,
        classLoader: ClassLoader,
        processName: String,
        phase: String,
    ) {
        if (!HostSymbols.isMainProcess(processName)) return
        val installedGetterHooks = lastPageBookShieldHooks.sumOf { spec ->
            installReturnHook(
                module,
                classLoader,
                phase,
                spec.className,
                spec.methodName,
                spec.location,
                spec.sourceShape,
            )
        }
        val installedMorphingHooks = if (isLastPageMorphingInstallPhase(phase)) {
            installMorphingHooks(module, classLoader, phase, processName)
        } else {
            FeatureHooks.i(
                "Book shield last page Morphing deferred: phase=$phase, process=$processName",
            )
            0
        }
        FeatureHooks.i(
            "Book shield last page phase finished: phase=$phase, process=$processName, " +
                "getters=$installedGetterHooks/${lastPageBookShieldHooks.size}, " +
                "morphing=$installedMorphingHooks/${lastPageMorphingMethodNames.size}",
        )
    }

    private fun installMorphingHooks(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
        processName: String,
    ): Int = runCatching {
        val installed = installLastPageMorphingHooks(module, classLoader, phase)
        FeatureHooks.i(
            "Book shield last page Morphing finished: phase=$phase, process=$processName, " +
                "installed=$installed/${lastPageMorphingMethodNames.size}",
        )
        installed
    }.getOrElse { throwable ->
        FeatureHooks.e(
            "Book shield last page Morphing install failed: phase=$phase, process=$processName",
            throwable,
        )
        0
    }

    private fun installLastPageMorphingHooks(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
    ): Int {
        val clazz = FeatureHooks.findClassOrNull(HostSymbols.Classes.MORPHING_ADAPTER, classLoader) ?: return 0
        return lastPageMorphingMethodNames.sumOf { methodName ->
            FeatureHooks.declaredMethodsNamed(clazz, methodName)
                .filter { method ->
                    method.parameterTypes.size == 1 &&
                        List::class.java.isAssignableFrom(method.parameterTypes[0])
                }
                .sumOf { method -> installLastPageMorphingMethod(module, method, phase) }
        }
    }

    private fun installLastPageMorphingMethod(
        module: HookModule,
        method: Method,
        phase: String,
    ): Int {
        val methodName = method.name
        val success = FeatureHooks.hookAround(
            module = module,
            executable = method,
            feature = "p2.bookShield.lastPageMorphing.$methodName",
            id = "qdhelper.p2.shield.last_page_morphing_$methodName",
        ) { chain ->
            val original = chain.args.getOrNull(0) as? List<*>
            if (
                original == null ||
                !isLastPageMorphingAdapterClassName(chain.thisObject?.javaClass?.name)
            ) {
                return@hookAround chain.proceed()
            }
            val replacement = filterLastPageMorphingCards(original)
            if (replacement !== original) {
                val nextArgs = chain.args.toTypedArray()
                nextArgs[0] = replacement
                chain.proceed(nextArgs)
            } else {
                chain.proceed()
            }
        }
        if (!success) return 0
        FeatureHooks.i(
            "Book shield last page installed MorphingAdapter.$methodName, phase=$phase",
        )
        return 1
    }

    private fun filterLastPageMorphingCards(cards: List<*>): List<*> {
        val context = HostSessionStore.contextOrNull() ?: return cards
        var rules: BookShieldRules? = null
        val trace = SettingsRuntime.isShieldTraceEnabled(context)
        return transformRoutedLastPageCards(
            cards = cards,
            resolveLocation = { card -> lastPageMorphingLocation(describeLastPageMorphingCard(card)) },
            isLocationEnabled = { location ->
                SettingsRuntime.isBookShieldLocationEnabled(context, location)
            },
            transform = { location, card ->
                val currentRules = rules ?: BookShieldRules.from(context).also { rules = it }
                if (!currentRules.hasRules) {
                    card
                } else {
                    val replacement = BookListFilter.filterSourceWithRules(location, card, currentRules, trace)
                    if (shouldRemoveEmptyReadMoreCard(card, replacement)) null else replacement
                }
            },
        )
    }

    private fun shouldRemoveEmptyReadMoreCard(original: Any?, replacement: Any?): Boolean {
        val descriptor = describeLastPageMorphingCard(original)
        return shouldRemoveEmptyReadMoreCard(
            descriptor = descriptor,
            originalListSize = morphingCardPrimaryListSize(original),
            replacementListSize = morphingCardPrimaryListSize(replacement),
        )
    }

    private fun morphingCardPrimaryListSize(card: Any?): Int? {
        val data = BookInfoExtractor.invokeNoArg(card, "getData") ?: return null
        return (BookInfoExtractor.invokeNoArg(data, "getList") as? List<*>)?.size
    }

    private fun describeLastPageMorphingCard(card: Any?): LastPageMorphingCardDescriptor {
        val data = BookInfoExtractor.invokeNoArg(card, "getData")
        val extension = BookInfoExtractor.invokeNoArg(data, "getExtension")
        return LastPageMorphingCardDescriptor(
            columnName = BookInfoExtractor.invokeNoArg(extension, "getColumnName") as? String,
            viewType = BookInfoExtractor.invokeNoArg(card, "getViewType") as? Int,
            trackViewType = BookInfoExtractor.invokeNoArg(card, "getTrackViewType") as? Int,
            multiDataSize = (BookInfoExtractor.invokeNoArg(data, "getMultiData") as? List<*>)?.size ?: 0,
        )
    }

    private fun installReturnHook(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
        className: String,
        methodName: String,
        location: String,
        sourceShape: LastPageBookShieldSourceShape,
    ): Int {
        val clazz = FeatureHooks.findClassOrNull(className, classLoader) ?: run {
            FeatureHooks.w("Book shield last page missing class: ${HostSymbols.classLabel(className)}, phase=$phase")
            return 0
        }
        val method = FeatureHooks.findDeclaredMethodOrNull(clazz, methodName) ?: run {
            FeatureHooks.w(
                "Book shield last page missing getter: ${HostSymbols.classLabel(className)}.$methodName, " +
                    "phase=$phase",
            )
            return 0
        }
        if (!acceptsLastPageBookShieldSource(method, sourceShape)) {
            FeatureHooks.w(
                "Book shield last page incompatible getter: ${HostSymbols.classLabel(className)}.$methodName " +
                    "returns=${method.returnType.name}, shape=$sourceShape, phase=$phase",
            )
            return 0
        }
        val id = "shield.${HostSymbols.classLabel(className)}.$methodName"
        val success = FeatureHooks.hookAround(
            module = module,
            executable = method,
            feature = "p2.bookShield.$id",
            id = "qdhelper.p2.$id",
        ) { chain ->
            val original = chain.proceed()
            val replacement = BookListFilter.filterSource(
                HostSessionStore.contextOrNull(),
                location,
                original,
            )
            if (replacement !== original) replacement else original
        }
        if (!success) return 0
        FeatureHooks.i(
            "Book shield last page installed $id: " +
                "${method.declaringClass.name}.${method.name}, phase=$phase",
        )
        return 1
    }
}

internal data class LastPageBookShieldHook(
    val className: String,
    val methodName: String,
    val location: String,
    val sourceShape: LastPageBookShieldSourceShape = LastPageBookShieldSourceShape.LIST,
)

internal enum class LastPageBookShieldSourceShape {
    LIST,
    CONTAINER,
}

internal fun acceptsLastPageBookShieldSource(
    method: Method,
    shape: LastPageBookShieldSourceShape,
): Boolean = when (shape) {
    LastPageBookShieldSourceShape.LIST -> List::class.java.isAssignableFrom(method.returnType)
    LastPageBookShieldSourceShape.CONTAINER ->
        method.returnType.name == HostSymbols.Classes.SIMILAR_RECOMMEND
}

internal val lastPageMorphingMethodNames = listOf(
    "updateList",
    "updateListNoDiff",
    "appendList",
    "addItems",
)

internal fun isLastPageMorphingInstallPhase(phase: String): Boolean = phase != "packageReady"

internal fun isLastPageMorphingAdapterClassName(className: String?): Boolean {
    return className == HostSymbols.Classes.LAST_PAGE_ADAPTER ||
        className == HostSymbols.Classes.LAST_PAGE_V4_ADAPTER
}

internal data class LastPageMorphingCardDescriptor(
    val columnName: String? = null,
    val viewType: Int? = null,
    val trackViewType: Int? = null,
    val multiDataSize: Int = 0,
)

internal fun lastPageMorphingLocation(card: LastPageMorphingCardDescriptor): String? {
    return when (card.columnName?.trim()?.lowercase()) {
        "kanguocishuderenhaikanguo" -> BookShieldLocations.READ_LAST_PAGE_SIMILAR_BOOK
        "yuedupianhao", "tuijianliyou" -> BookShieldLocations.READ_LAST_PAGE_READ_MORE
        else -> if (card.viewType == 14 && card.trackViewType == 107 && card.multiDataSize > 0) {
            BookShieldLocations.READ_LAST_PAGE_RECOMMEND_BY_TAG
        } else {
            null
        }
    }
}

internal fun shouldRemoveEmptyReadMoreCard(
    descriptor: LastPageMorphingCardDescriptor,
    originalListSize: Int?,
    replacementListSize: Int?,
): Boolean {
    return descriptor.columnName.equals("tuijianliyou", ignoreCase = true) &&
        originalListSize != null &&
        originalListSize > 0 &&
        replacementListSize == 0
}

internal fun <T> transformRoutedLastPageCards(
    cards: List<T>,
    resolveLocation: (T) -> String?,
    isLocationEnabled: (String) -> Boolean,
    transform: (String, T) -> T?,
): List<T> {
    var changed = false
    val next = ArrayList<T>(cards.size)
    cards.forEach { card ->
        val location = resolveLocation(card)
        val replacement = if (location != null && isLocationEnabled(location)) {
            transform(location, card)
        } else {
            card
        }
        if (replacement !== card) changed = true
        if (replacement != null) next += replacement
    }
    return if (changed) next else cards
}

internal val lastPageBookShieldHooks = listOf(
    LastPageBookShieldHook(
        HostSymbols.Classes.BOOK_LAST_PAGE,
        "getAlsoReadList",
        BookShieldLocations.READ_LAST_PAGE_SIMILAR_BOOK,
    ),
    LastPageBookShieldHook(
        HostSymbols.Classes.BOOK_LAST_PAGE,
        "getRecommendList",
        BookShieldLocations.READ_LAST_PAGE_RECOMMEND,
    ),
    LastPageBookShieldHook(
        HostSymbols.Classes.BOOK_LAST_PAGE,
        "getRelatedBookList",
        BookShieldLocations.READ_LAST_PAGE_RECOMMEND_BY_TAG,
    ),
    LastPageBookShieldHook(
        HostSymbols.Classes.BOOK_LAST_PAGE,
        "getTagRecommendList",
        BookShieldLocations.READ_LAST_PAGE_RECOMMEND_BY_TAG,
    ),
    LastPageBookShieldHook(
        HostSymbols.Classes.BOOK_LAST_PAGE,
        "getSimilarRecommend",
        BookShieldLocations.READ_LAST_PAGE_SIMILAR_BOOK,
        LastPageBookShieldSourceShape.CONTAINER,
    ),
)
