package com.xiyunmn.qdhelper.feature.bookshield.hook

import com.xiyunmn.qdhelper.feature.bookshield.model.BookInfoExtractor
import com.xiyunmn.qdhelper.feature.bookshield.policy.BookListFilter
import com.xiyunmn.qdhelper.feature.bookshield.policy.BookShieldLocations
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.host.api.target.HostTargetResolver
import com.xiyunmn.qdhelper.host.api.target.ShieldTargetKeys
import com.xiyunmn.qdhelper.host.api.target.TargetKey
import com.xiyunmn.qdhelper.feature.api.runtime.InstallableFeature
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols as QDReaderHost
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import java.lang.reflect.Method

object BookShieldFeature : InstallableFeature {
    override val name: String = "BookShieldFeature"

    override fun install(
        module: HookModule,
        classLoader: ClassLoader,
        processName: String,
        phase: String,
    ) {
        if (!QDReaderHost.isMainProcess(processName)) {
            return
        }
        var installedHooks = 0
        installedHooks += installMorphingHooks(module, classLoader, phase)
        installedHooks += installListArgHook(module, classLoader, processName, phase, ShieldTargetKeys.bookstoreReborn, QDReaderHost.Classes.BOOKSTORE_REBORN_ADAPTER, "setItems", BookShieldLocations.BOOKSTORE_REBORN)
        installedHooks += installListArgHook(
            module,
            classLoader,
            processName,
            phase,
            ShieldTargetKeys.bookItem,
            QDReaderHost.Classes.BOOK_ITEM_ADAPTER,
            "setList",
            BookShieldLocations.BOOKSTORE_REBORN,
            adapterGuard = { adapter, _ -> isBookListActivityBookItemAdapter(adapter) },
        )
        installedHooks += installListArgHook(module, classLoader, processName, phase, ShieldTargetKeys.bookstoreItem, QDReaderHost.Classes.QD_BOOKSTORE_ITEM_ADAPTER, "setList", BookShieldLocations.CATEGORY_ALL)
        installedHooks += installListArgHook(module, classLoader, processName, phase, ShieldTargetKeys.bookstoreGroup, QDReaderHost.Classes.BOOKSTORE_GROUP_ADAPTER, "setData", BookShieldLocations.CATEGORY_RECOMMEND)
        installedHooks += installListArgHook(module, classLoader, processName, phase, ShieldTargetKeys.bookstoreLimit, QDReaderHost.Classes.BOOKSTORE_LIMIT_ADAPTER, "setData", BookShieldLocations.FREE_RECOMMEND)
        installedHooks += installListArgHook(module, classLoader, processName, phase, ShieldTargetKeys.bookRecommend, QDReaderHost.Classes.QD_BOOK_RECOMMEND_ADAPTER, "s", BookShieldLocations.HOT_RECOMMEND)
        installedHooks += installListArgHook(module, classLoader, processName, phase, ShieldTargetKeys.sanJiang, QDReaderHost.Classes.SAN_JIANG_ADAPTER, "p", BookShieldLocations.BOOKSTORE_REBORN)
        installedHooks += installListArgHook(module, classLoader, processName, phase, ShieldTargetKeys.rankingRight, QDReaderHost.Classes.RANKING_RIGHT_ADAPTER, "G", BookShieldLocations.RANKING)
        installedHooks += installListArgHook(module, classLoader, processName, phase, ShieldTargetKeys.comicSquare, QDReaderHost.Classes.COMIC_SQUARE_ADAPTER, "p", BookShieldLocations.COMIC)
        installedHooks += BookStoreFlutterShield.install(module, classLoader, phase)
        installedHooks += DailyReadingFlutterShield.install(module, classLoader, phase)
        FeatureHooks.i("Book shield phase finished: phase=$phase, process=$processName, installedHooks=$installedHooks")
    }

    private fun installMorphingHooks(module: HookModule, classLoader: ClassLoader, phase: String): Int {
        val clazz = FeatureHooks.findClassOrNull(QDReaderHost.Classes.MORPHING_ADAPTER, classLoader) ?: return 0
        var count = 0
        listOf("updateList", "updateListNoDiff", "appendList", "addItems").forEach { methodName ->
            FeatureHooks.declaredMethodsNamed(clazz, methodName)
                .filter { it.parameterTypes.size == 1 && List::class.java.isAssignableFrom(it.parameterTypes[0]) }
                .forEach { method ->
                    count += installListArgMethod(
                        module = module,
                        id = "shield.morphing.$methodName",
                        method = method,
                        phase = phase,
                        hookId = "qdhelper.p2.shield.morphing_$methodName",
                        location = BookShieldLocations.SELECTION_MORPHING,
                        locationResolver = { adapter -> morphingBookShieldLocation(adapter)!! },
                        // MorphingAdapter 也用于搜索首页。仅允许精选标记或已确认的书籍 Activity，
                        // 防止按通用 MorphingCard 类型把搜索等非书城信息流纳入过滤。
                        adapterGuard = { adapter, _ ->
                            !isLastPageMorphingAdapterClassName(adapter?.javaClass?.name) &&
                                morphingBookShieldLocation(adapter) != null
                        },
                    )
                }
        }
        return count
    }

    private fun installListArgHook(
        module: HookModule,
        classLoader: ClassLoader,
        processName: String,
        phase: String,
        targetKey: TargetKey.MethodTarget,
        className: String,
        methodName: String,
        location: String,
        adapterGuard: (Any?, List<*>) -> Boolean = { _, _ -> true },
    ): Int {
        var count = 0
        HostTargetResolver.resolveMethods(
            context = HostSessionStore.contextOrNull(),
            classLoader = classLoader,
            processName = processName,
            key = targetKey,
        )
            .forEach { method ->
                count += installListArgMethod(
                    module = module,
                    id = "shield.${className.substringAfterLast('.')}.$methodName",
                    method = method,
                    phase = phase,
                    hookId = "qdhelper.p2.shield.${className.substringAfterLast('.').lowercase()}_$methodName",
                    location = location,
                    adapterGuard = adapterGuard,
                )
            }
        return count
    }

    private fun installListArgMethod(
        module: HookModule,
        id: String,
        method: Method,
        phase: String,
        hookId: String,
        location: String,
        adapterGuard: (Any?, List<*>) -> Boolean = { _, _ -> true },
        locationResolver: (Any?) -> String = { location },
    ): Int {
        val success = FeatureHooks.hookAround(
            module = module,
            executable = method,
            feature = "p2.bookShield.$id",
            id = hookId,
        ) { chain ->
            val original = chain.args.getOrNull(0) as? List<*>
            if (original == null || !adapterGuard(chain.thisObject, original)) {
                return@hookAround chain.proceed()
            }
            val resolvedLocation = locationResolver(chain.thisObject)
            val replacement = BookListFilter.filterSource(HostSessionStore.contextOrNull(), resolvedLocation, original)
            if (replacement !== original && replacement is List<*>) {
                val nextArgs = chain.args.toTypedArray()
                nextArgs[0] = replacement
                chain.proceed(nextArgs)
            } else {
                chain.proceed()
            }
        }
        if (!success) {
            return 0
        }
        FeatureHooks.i("Book shield installed $id: ${method.declaringClass.name}.${method.name}, phase=$phase")
        return 1
    }

    private fun isSelectionMorphingAdapter(adapter: Any?): Boolean {
        return BookInfoExtractor.invokeNoArg(adapter, "isFromMorphingFragment") == true
    }

    private fun morphingBookShieldLocation(adapter: Any?): String? {
        return morphingBookShieldLocation(
            isFromSelection = isSelectionMorphingAdapter(adapter),
            contextClassName = BookInfoExtractor.fieldValue(adapter, "ctx")?.javaClass?.name,
            isActivity = BookInfoExtractor.invokeNoArg(adapter, "isActivity") == true,
        )
    }

    private fun isBookListActivityBookItemAdapter(adapter: Any?): Boolean {
        val contextClassName = BookInfoExtractor.fieldValue(adapter, "ctx")?.javaClass?.name
        return isBookListActivityBookItemAdapterContext(contextClassName)
    }

}

internal fun isBookListActivityBookItemAdapterContext(contextClassName: String?): Boolean =
    contextClassName == QDReaderHost.Classes.BOOK_LIST_ACTIVITY

internal fun morphingBookShieldLocation(
    isFromSelection: Boolean,
    contextClassName: String? = null,
    isActivity: Boolean = false,
): String? {
    if (isFromSelection) {
        return BookShieldLocations.SELECTION_MORPHING
    }
    return BookShieldLocations.BOOKSTORE_REBORN.takeIf {
        isActivity || contextClassName in BOOK_MORPHING_HOSTS
    }
}

private val BOOK_MORPHING_HOSTS = setOf(
    QDReaderHost.Classes.MORPHING_ACTIVITY,
    QDReaderHost.Classes.BOOK_CATEGORY_PAGE_ACTIVITY,
    QDReaderHost.Classes.BOOK_FRIENDS_ACTIVITY,
)
