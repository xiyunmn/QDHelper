package com.xiyunmn.qdhelper.feature.reader

import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.host.api.target.HostTargetResolver
import com.xiyunmn.qdhelper.feature.api.runtime.InstallableFeature
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols as QDReaderHost
import com.xiyunmn.qdhelper.host.api.target.ReaderTargetKeys
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import java.lang.reflect.Method
import java.util.Collections
import java.util.Vector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object ReadPageCleanFeature : InstallableFeature {
    override val name: String = "ReadPageCleanFeature"

    private val hitCounters = ConcurrentHashMap<String, AtomicLong>()

    override fun install(
        module: HookModule,
        classLoader: ClassLoader,
        processName: String,
        phase: String,
    ) {
        var installedHooks = 0

        installedHooks += installChapterEndAppendGate(module, classLoader, phase)
        installedHooks += installLastPageListEmpty(module, classLoader, phase, "readPage.lastPage.alsoRead", "getAlsoReadList")
        installedHooks += installLastPageListEmpty(module, classLoader, phase, "readPage.lastPage.recommend", "getRecommendList")
        installedHooks += installLastPageListEmpty(module, classLoader, phase, "readPage.lastPage.related", "getRelatedBookList")
        installedHooks += installLastPageListEmpty(module, classLoader, phase, "readPage.lastPage.tags", "getTagRecommendList")
        installedHooks += installLastPageListEmpty(module, classLoader, phase, "readPage.lastPage.derivation", "getRelatedAround")
        installedHooks += installLastPageNull(module, classLoader, phase, "readPage.lastPage.tryRead", "getTryReadInfo")
        installedHooks += installLastPageNull(module, classLoader, phase, "readPage.lastPage.circle", "getBookCircleInfo")
        installedHooks += installLastPageNull(module, classLoader, phase, "readPage.lastPage.similar", "getSimilarRecommend")
        installedHooks += installLastPageV4Null(module, classLoader, phase, "readPage.lastPageV4.recommend", "getRecommend")
        installedHooks += installLastPageV4Null(module, classLoader, phase, "readPage.lastPageV4.circle", "getBookCircleInfo")
        installedHooks += installReaderToolbarAdGate(module, classLoader, phase)

        FeatureHooks.i("P1 read-page clean phase finished: phase=$phase, process=$processName, installedHooks=$installedHooks")
    }

    private fun installChapterEndAppendGate(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
    ): Int {
        val method = HostTargetResolver.resolveMethod(
            context = HostSessionStore.contextOrNull(),
            classLoader = classLoader,
            processName = HostSessionStore.processName,
            key = ReaderTargetKeys.chapterEndAppend,
        ) ?: return 0
        return installReturnValue(
            module = module,
            id = "readPage.chapterEnd.append",
            method = method,
            hookId = "qdhelper.p1.read.chapter_end_append",
            phase = phase,
            shouldBlock = { SettingsRuntime.isChapterEndCleanEnabled(HostSessionStore.contextOrNull()) },
            action = "return-original-pages",
        ) { args ->
            existingPagesOrEmpty(args.firstOrNull())
        }
    }

    private fun installLastPageListEmpty(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
        id: String,
        methodName: String,
    ): Int {
        val clazz = FeatureHooks.findClassOrNull(QDReaderHost.Classes.BOOK_LAST_PAGE, classLoader) ?: return 0
        val method = FeatureHooks.findDeclaredMethodOrNull(clazz, methodName) ?: return 0
        if (!List::class.java.isAssignableFrom(method.returnType)) {
            FeatureHooks.w("Skip $id: unexpected return type=${method.returnType.name}")
            return 0
        }
        return installReturnValue(
            module = module,
            id = id,
            method = method,
            hookId = "qdhelper.p1.$id",
            phase = phase,
            shouldBlock = { SettingsRuntime.isLastPageCleanEnabled(HostSessionStore.contextOrNull()) },
            action = "return-empty-list",
        ) {
            Collections.emptyList<Any>()
        }
    }

    private fun installLastPageNull(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
        id: String,
        methodName: String,
    ): Int {
        val clazz = FeatureHooks.findClassOrNull(QDReaderHost.Classes.BOOK_LAST_PAGE, classLoader) ?: return 0
        val method = FeatureHooks.findDeclaredMethodOrNull(clazz, methodName) ?: return 0
        return installNullableNull(module, id, method, phase)
    }

    private fun installLastPageV4Null(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
        id: String,
        methodName: String,
    ): Int {
        val clazz = FeatureHooks.findClassOrNull(QDReaderHost.Classes.BOOK_LAST_PAGE_V4, classLoader) ?: return 0
        val method = FeatureHooks.findDeclaredMethodOrNull(clazz, methodName) ?: return 0
        return installNullableNull(module, id, method, phase)
    }

    private fun installReaderToolbarAdGate(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
    ): Int {
        val clazz = FeatureHooks.findClassOrNull(QDReaderHost.Classes.READ_MENU_DATA, classLoader) ?: return 0
        val method = FeatureHooks.findDeclaredMethodOrNull(clazz, "getAdv") ?: return 0
        if (method.returnType.isPrimitive) {
            FeatureHooks.w("Skip readPage.toolbarAd: primitive return type=${method.returnType.name}")
            return 0
        }
        return installReturnValue(
            module = module,
            id = "readPage.toolbarAd",
            method = method,
            hookId = "qdhelper.p1.read.toolbar_ad",
            phase = phase,
            shouldBlock = { SettingsRuntime.isReaderToolbarAdHidden(HostSessionStore.contextOrNull()) },
            action = "return-null",
        ) {
            null
        }
    }

    private fun installNullableNull(
        module: HookModule,
        id: String,
        method: Method,
        phase: String,
    ): Int {
        if (method.returnType.isPrimitive) {
            FeatureHooks.w("Skip $id: primitive return type=${method.returnType.name}")
            return 0
        }
        return installReturnValue(
            module = module,
            id = id,
            method = method,
            hookId = "qdhelper.p1.$id",
            phase = phase,
            shouldBlock = { SettingsRuntime.isLastPageCleanEnabled(HostSessionStore.contextOrNull()) },
            action = "return-null",
        ) {
            null
        }
    }

    private fun installReturnValue(
        module: HookModule,
        id: String,
        method: Method,
        hookId: String,
        phase: String,
        shouldBlock: () -> Boolean,
        action: String,
        value: (List<Any?>) -> Any?,
    ): Int {
        val installKey = "$id:${method.toGenericString()}"
        val success = FeatureHooks.hookAround(
            module = module,
            executable = method,
            feature = "p1.readClean.$id:${method.ownerLabel()}.${method.name}",
            id = hookId,
        ) { chain ->
            if (!shouldBlock()) {
                return@hookAround chain.proceed()
            }
            logHit(id, installKey, action)
            value(chain.args)
        }
        if (!success) {
            return 0
        }

        FeatureHooks.i("P1 read clean installed $id: ${method.ownerLabel()}.${method.name}, phase=$phase")
        return 1
    }

    private fun existingPagesOrEmpty(divideData: Any?): Any {
        if (divideData == null) {
            return Vector<Any>()
        }
        return runCatching {
            // The chapter-content holder (7.9.469 "va.judian") obfuscates its existing-pages getter
            // letter, so instead of locking the name "d" we pick the sole no-arg method that returns a
            // Vector (its sibling no-arg getters return ArrayList / JSONObject / primitives, so the
            // Vector return type disambiguates uniquely across versions).
            val method = divideData.javaClass.methods.firstOrNull { candidate ->
                candidate.parameterCount == 0 && Vector::class.java.isAssignableFrom(candidate.returnType)
            } ?: FeatureHooks.allDeclaredMethods(divideData.javaClass).firstOrNull { candidate ->
                candidate.parameterCount == 0 && Vector::class.java.isAssignableFrom(candidate.returnType)
            }
                ?: return Vector<Any>()
            method.invoke(divideData) ?: Vector<Any>()
        }.getOrElse { throwable ->
            FeatureHooks.w("Read original chapter pages failed: ${throwable.javaClass.name}")
            Vector<Any>()
        }
    }

    private fun logHit(id: String, installKey: String, action: String) {
        val count = hitCounters.computeIfAbsent(installKey) { AtomicLong(0L) }.incrementAndGet()
        if (count <= 20L || count % 50L == 0L) {
            FeatureHooks.i("P1 hit $id#$count: action=$action, thread=${Thread.currentThread().name}")
        }
    }

    private fun Method.ownerLabel(): String {
        return QDReaderHost.classLabel(declaringClass.name)
    }
}
