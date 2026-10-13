package com.xiyunmn.qdhelper.feature.bookshelf

import com.xiyunmn.qdhelper.feature.api.runtime.UiFeatureSupport

import android.view.View
import android.widget.ImageView
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.host.api.target.HostTargetResolver
import com.xiyunmn.qdhelper.host.api.target.BookshelfTargetKeys
import com.xiyunmn.qdhelper.host.api.target.ReaderFontTargetKeys
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols as QDReaderHost
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.feature.api.runtime.HookChain
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 书架悬浮广告、顶部活动和左上角活动卡片清理。
 */
object BookshelfCleaner {
    private val topOperationClearEventAt = AtomicLong(0L)
    private val defaultTopBackgroundCall = ThreadLocal<Int>()
    private val defaultTopBackgroundIds = Collections.synchronizedMap(WeakHashMap<ImageView, Int>())
    private val appliedTopBackgrounds =
        Collections.synchronizedMap(WeakHashMap<ImageView, AppliedTopBackground>())

    fun install(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
    ): Int {
        var installedHooks = 0
        installedHooks += installBookshelfHoverAdGate(module, classLoader, phase)
        installedHooks += installBookshelfTopActivityOperationGate(module, classLoader, phase)
        installedHooks += installBookshelfDefaultTopBackgroundCapture(module, classLoader, phase)
        installedHooks += installBookshelfTopBackgroundImageGate(module, classLoader, phase)
        return installedHooks
    }

    private fun installBookshelfDefaultTopBackgroundCapture(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
    ): Int {
        val clazz = FeatureHooks.findClassOrNull(QDReaderHost.Classes.BOOKSHELF_REBORN_FRAGMENT, classLoader)
            ?: return 0
        val method = HostTargetResolver.resolveMethod(
            context = HostSessionStore.contextOrNull(),
            classLoader = classLoader,
            processName = HostSessionStore.processName,
            key = BookshelfTargetKeys.defaultTopBackgroundLoad,
        ) ?: return 0
        val success = FeatureHooks.hookAround(
            module = module,
            executable = method,
            feature = "ui.bookshelf.topActivity.defaultBackground.capture:${method.declaringClass.simpleName}.${method.name}",
            id = "qdhelper.p2.ui.bookshelf_top_default_background_capture",
        ) { chain ->
            val resourceId = chain.args.getOrNull(0) as? Int ?: return@hookAround chain.proceed()
            val previous = defaultTopBackgroundCall.get()
            defaultTopBackgroundCall.set(resourceId)
            try {
                chain.proceed()
            } finally {
                if (previous == null) {
                    defaultTopBackgroundCall.remove()
                } else {
                    defaultTopBackgroundCall.set(previous)
                }
            }
        }
        if (success) {
            FeatureHooks.i("Bookshelf default top background capture installed: phase=$phase")
            return 1
        }
        return 0
    }

    private fun installBookshelfTopBackgroundImageGate(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
    ): Int {
        val clazz = FeatureHooks.findClassOrNull(QDReaderHost.Classes.YW_IMAGE_LOADER, classLoader) ?: return 0
        val method = FeatureHooks.declaredMethodsNamed(clazz, "A")
            .singleOrNull { candidate ->
                candidate.parameterTypes.size == 7 &&
                    View::class.java.isAssignableFrom(candidate.parameterTypes[0]) &&
                    candidate.parameterTypes[1] == Any::class.java &&
                    candidate.returnType == Void.TYPE
            }
            ?: return 0
        val success = FeatureHooks.hookAround(
            module = module,
            executable = method,
            feature = "ui.bookshelf.topActivity.backgroundImage:${method.declaringClass.simpleName}.${method.name}",
            id = "qdhelper.p2.ui.bookshelf_top_background_image",
        ) { chain ->
            val target = chain.args.getOrNull(0) as? ImageView ?: return@hookAround chain.proceed()
            if (!SettingsRuntime.isBookshelfTopActivityAdHidden(HostSessionStore.contextOrNull()) ||
                target.resourceEntryName() != "ivHeadBg"
            ) {
                return@hookAround chain.proceed()
            }
            val backgroundId = defaultTopBackgroundCall.get()
                ?: defaultTopBackgroundIds[target]
                ?: return@hookAround chain.proceed()
            defaultTopBackgroundIds[target] = backgroundId
            val args = chain.args.toMutableList()
            args[1] = backgroundId
            FeatureHooks.i(
                "P2 hit ui.bookshelf.topActivity.backgroundImage: " +
                    "action=replace-host-default-background, resourceId=$backgroundId",
            )
            chain.proceed(args.toTypedArray())
        }
        if (success) {
            FeatureHooks.i("Bookshelf top background image gate installed: phase=$phase")
            return 1
        }
        return 0
    }

    private fun installBookshelfHoverAdGate(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
    ): Int {
        val clazz = FeatureHooks.findClassOrNull(QDReaderHost.Classes.BOOKSHELF_HOVER_AD_HELPER, classLoader) ?: return 0
        val method = FeatureHooks.declaredMethodsNamed(clazz, "showBookShelfHoverAd")
            .singleOrNull { candidate -> candidate.parameterTypes.size == 2 && candidate.returnType == Void.TYPE }
            ?: return 0

        return UiFeatureSupport.installReturnValue(
            module = module,
            id = "ui.bookshelf.hoverAd",
            method = method,
            hookId = "qdhelper.p2.ui.bookshelf_hover_ad",
            phase = phase,
            shouldBlock = { _ -> SettingsRuntime.isBookshelfHoverAdHidden(HostSessionStore.contextOrNull()) },
            action = "callback-null",
        ) { chain ->
            invokeOneArgCallback(chain.args.getOrNull(1), null)
            null
        }
    }

    private fun installBookshelfTopActivityOperationGate(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
    ): Int {
        val clazz = FeatureHooks.findClassOrNull(QDReaderHost.Classes.BOOKSHELF_OPERATION_MANAGER, classLoader)
        var installedHooks = 0
        val shouldHide = { _: HookChain ->
            SettingsRuntime.isBookshelfTopActivityAdHidden(HostSessionStore.contextOrNull())
        }

        clazz?.let { FeatureHooks.findDeclaredMethodOrNull(it, "getBookShelfOperation") }?.let { method ->
            installedHooks += UiFeatureSupport.installReturnValue(
                module = module,
                id = "ui.bookshelf.topActivity.operation.get",
                method = method,
                hookId = "qdhelper.p2.ui.bookshelf_top_operation_get",
                phase = phase,
                shouldBlock = shouldHide,
                action = "return-null",
            ) {
                val managerClass = method.declaringClass
                if (clearBookShelfOperationManagerState(managerClass)) {
                    notifyBookshelfTopOperationCleared(managerClass)
                }
                null
            }
        }
        clazz?.let { FeatureHooks.findDeclaredMethodOrNull(it, "getBookShelfOperationRes") }?.let { method ->
            installedHooks += UiFeatureSupport.installReturnValue(
                module = module,
                id = "ui.bookshelf.topActivity.operation.fetch",
                method = method,
                hookId = "qdhelper.p2.ui.bookshelf_top_operation_fetch",
                phase = phase,
                shouldBlock = shouldHide,
                action = "skip-network",
            ) {
                val managerClass = method.declaringClass
                if (clearBookShelfOperationManagerState(managerClass, markFetched = true)) {
                    notifyBookshelfTopOperationCleared(managerClass)
                }
                null
            }
        }
        clazz?.let { FeatureHooks.declaredMethodsNamed(it, "setBookShelfOperation") }.orEmpty()
            .singleOrNull { it.parameterTypes.size == 1 }
            ?.let { method ->
                installedHooks += UiFeatureSupport.installReturnValue(
                    module = module,
                    id = "ui.bookshelf.topActivity.operation.set",
                    method = method,
                    hookId = "qdhelper.p2.ui.bookshelf_top_operation_set",
                    phase = phase,
                    shouldBlock = shouldHide,
                action = "clear-state",
            ) {
                    val managerClass = method.declaringClass
                    if (clearBookShelfOperationManagerState(managerClass)) {
                        notifyBookshelfTopOperationCleared(managerClass)
                    }
                    null
                }
            }
        HostTargetResolver.resolveMethod(
                context = HostSessionStore.contextOrNull(),
                classLoader = classLoader,
                processName = HostSessionStore.processName,
                key = BookshelfTargetKeys.operationParseLambda,
            )
            ?.let { method ->
                installedHooks += UiFeatureSupport.installReturnValue(
                    module = module,
                    id = "ui.bookshelf.topActivity.operation.parse",
                    method = method,
                    hookId = "qdhelper.p2.ui.bookshelf_top_operation_parse",
                    phase = phase,
                    shouldBlock = shouldHide,
                    action = "drop-response",
                ) {
                    val managerClass = method.declaringClass
                    if (clearBookShelfOperationManagerState(managerClass, markFetched = true)) {
                        notifyBookshelfTopOperationCleared(managerClass)
                    }
                    null
                }
            }
        return installedHooks
    }

    fun applyBookshelfLeftTopActivityCard(root: View): Boolean {
        if (!SettingsRuntime.isBookshelfLeftTopActivityCardHidden(HostSessionStore.contextOrNull())) {
            return false
        }
        var changed = hideViewsByIdName(
            root,
            "bookshelfOperationContainer",
            "operationTipIcon",
        )
        UiFeatureSupport.findChildViewByIdName(root, "readingTimeView")?.let { readingTimeView ->
            if (readingTimeView.visibility != View.VISIBLE) {
                readingTimeView.visibility = View.VISIBLE
                changed = true
            }
        }
        return changed
    }

    fun applyBookshelfTopActivityBackground(root: View): Boolean {
        if (!SettingsRuntime.isBookshelfTopActivityAdHidden(HostSessionStore.contextOrNull())) {
            return false
        }
        val headBackground = UiFeatureSupport.findChildViewByIdName(root, "ivHeadBg") as? ImageView ?: return false
        val resourceId = defaultTopBackgroundIds[headBackground] ?: return false
        val previous = appliedTopBackgrounds[headBackground]
        if (previous?.resourceId == resourceId && headBackground.drawable === previous.drawable.get()) {
            return false
        }
        headBackground.setImageResource(resourceId)
        headBackground.visibility = View.VISIBLE
        headBackground.drawable?.let { drawable ->
            appliedTopBackgrounds[headBackground] = AppliedTopBackground(resourceId, WeakReference(drawable))
        }
        return true
    }

    private fun hideViewsByIdName(root: View, vararg idNames: String): Boolean {
        var changed = false
        idNames.forEach { idName ->
            UiFeatureSupport.findChildViewByIdName(root, idName)?.let { view ->
                if (view.visibility != View.GONE) {
                    view.visibility = View.GONE
                    changed = true
                }
            }
        }
        return changed
    }

    private fun clearBookShelfOperationManagerState(
        managerClass: Class<*>,
        markFetched: Boolean = false,
    ): Boolean {
        var changed = false
        runCatching {
            val field = FeatureHooks.findFieldOrNull(managerClass, "bookShelfOperation") ?: return@runCatching
            if (FeatureHooks.read(field, null) != null) {
                changed = true
            }
            FeatureHooks.write(field, null, null)
        }.onFailure { throwable ->
            FeatureHooks.w("P2 clear bookshelf operation object failed: ${throwable.javaClass.name}")
        }
        listOf("lastIsAnniversaryType", "isForceClearMainTopOperation").forEach { fieldName ->
            runCatching {
                val field = FeatureHooks.findFieldOrNull(managerClass, fieldName) ?: return@runCatching
                if (FeatureHooks.read(field, null) == true) {
                    changed = true
                    FeatureHooks.write(field, null, false)
                }
            }
        }
        if (markFetched) {
            FeatureHooks.findFieldOrNull(managerClass, "getOperationTimeStamp")?.let { field ->
                FeatureHooks.write(field, null, System.currentTimeMillis())
            }
        }
        return changed
    }

    private fun notifyBookshelfTopOperationCleared(managerClass: Class<*>) {
        val now = System.currentTimeMillis()
        val last = topOperationClearEventAt.get()
        if (now - last < 1500L || !topOperationClearEventAt.compareAndSet(last, now)) {
            return
        }
        val notifiedByBus = runCatching {
            val classLoader = managerClass.classLoader ?: error("manager-classloader-not-found")
            val busGetter = HostTargetResolver.resolveMethod(
                context = HostSessionStore.contextOrNull(),
                classLoader = classLoader,
                processName = HostSessionStore.processName,
                key = ReaderFontTargetKeys.eventBusGetter,
            ) ?: error("bus-getter-not-found")
            val eventHandler = HostTargetResolver.resolveMethod(
                context = HostSessionStore.contextOrNull(),
                classLoader = classLoader,
                processName = HostSessionStore.processName,
                key = BookshelfTargetKeys.topOperationEventHandler,
            ) ?: error("event-handler-not-found")
            val constructor = FeatureHooks.findConstructorOrNull(eventHandler.parameterTypes[0], String::class.java)
                ?: error("event-constructor-not-found")
            val event = FeatureHooks.newInstance(constructor, "EVENT_CLEAR_MAIN_TOP_OPERATION")
                ?: error("event-instance-null")
            val bus = FeatureHooks.invoke(busGetter, null) ?: error("bus-instance-null")
            val postMethod = bus.javaClass.methods.firstOrNull { method ->
                method.name == "f" && method.parameterTypes.size == 1
            } ?: error("post-method-not-found")
            FeatureHooks.invoke(postMethod, bus, event)
        }.isSuccess
        val notified = notifiedByBus || listOf(
            "getBookShelfOperationRes\$lambda-13\$lambda-12",
            "getBookShelfOperationRes\$lambda-0",
        ).any { methodName ->
            runCatching {
                val method = HostTargetResolver.resolveMethod(
                    context = HostSessionStore.contextOrNull(),
                    classLoader = managerClass.classLoader ?: error("manager-classloader-not-found"),
                    processName = HostSessionStore.processName,
                    key = operationNotifyKey(methodName),
                )
                    ?: error("notify-method-not-found")
                FeatureHooks.invoke(method, null)
            }.isSuccess
        }
        if (!notified) {
            FeatureHooks.w("P2 notify bookshelf operation clear event failed: method-not-found")
        }
    }

    private fun operationNotifyKey(methodName: String) = when (methodName) {
        "getBookShelfOperationRes\$lambda-13\$lambda-12" -> BookshelfTargetKeys.operationNotifyLambda13_12
        "getBookShelfOperationRes\$lambda-0" -> BookshelfTargetKeys.operationNotifyLambda0
        else -> error("Unregistered bookshelf operation method: $methodName")
    }

    private fun invokeOneArgCallback(callback: Any?, arg: Any?) {
        callback ?: return
        runCatching {
            callback.javaClass.methods.firstOrNull { method ->
                method.name == "invoke" && method.parameterTypes.size == 1
            }?.invoke(callback, arg)
        }.onFailure { throwable ->
            FeatureHooks.w("P2 invoke callback failed: ${throwable.javaClass.name}")
        }
    }

    private fun View?.resourceEntryName(): String? {
        this ?: return null
        if (id == View.NO_ID) return null
        return runCatching { resources.getResourceEntryName(id) }.getOrNull()
    }

    private data class AppliedTopBackground(
        val resourceId: Int,
        val drawable: WeakReference<Any>,
    )
}
