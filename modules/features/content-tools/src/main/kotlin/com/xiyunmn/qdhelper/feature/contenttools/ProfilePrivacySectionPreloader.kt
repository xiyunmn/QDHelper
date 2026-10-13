package com.xiyunmn.qdhelper.feature.contenttools

import android.content.Context
import android.os.Handler
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import com.xiyunmn.qdhelper.host.api.reflection.HostAccess
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Proxy
import java.util.ArrayList
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

internal class ProfilePrivacySectionPreloader(
    private val lifecycle: ProfilePrivacyPreloadLifecycle,
    private val mainHandler: () -> Handler,
) {
    private companion object {
        const val HOST_DATA_KEY = "Data"
        const val HOST_RESULT_KEY = "Result"
        const val COUNT_KEY = "Count"
        const val TOTAL_COUNT_KEY = "TotalCount"
        const val CHAPTER_REVIEW_LIST_KEY = "ChapterReviewList"
        const val BOOK_REVIEW_LIST_KEY = "Items"
        const val COLUMN_LIST_KEY = "ColumnList"
        const val BOOK_LIST_KEY = "BookLists"
        const val CALLBACK_REQUEST_TIMEOUT_MS = 12_000L
    }

    private val requestIds = AtomicLong(0L)
    private val preloadInFlight = ConcurrentHashMap<PreloadKey, Long>()
    private val preloadCompleted = ConcurrentHashMap.newKeySet<PreloadKey>()
    private val pendingPreloadCallbacks = Collections.synchronizedMap(WeakHashMap<Any, PreloadRequest>())

    fun install(module: HookModule, metadata: RestoreMetadata): Int {
        var hooks = 0
        if (FeatureHooks.hookAround(
                module = module,
                executable = metadata.preloadCallbackSuccessMethod,
                feature = "profile.privacyRestore:preload callback success",
                id = "qdhelper.profile.privacy_restore.preload_callback.success",
            ) { chain ->
                val request = synchronized(pendingPreloadCallbacks) {
                    pendingPreloadCallbacks.remove(chain.thisObject)
                } ?: return@hookAround chain.proceed()
                handlePreloadSuccess(request, chain.args.getOrNull(0) as? JSONObject, metadata)
                null
            }
        ) {
            hooks += 1
        }
        val errorMethod = metadata.preloadCallbackErrorMethod
        if (errorMethod != null && FeatureHooks.hookAround(
                module = module,
                executable = errorMethod,
                feature = "profile.privacyRestore:preload callback error",
                id = "qdhelper.profile.privacy_restore.preload_callback.error",
            ) { chain ->
                val request = synchronized(pendingPreloadCallbacks) {
                    pendingPreloadCallbacks.remove(chain.thisObject)
                } ?: return@hookAround chain.proceed()
                finishPreload(request, success = false)
                FeatureHooks.w("Profile privacy preload failed: kind=${request.kind}, userId=<redacted>")
                null
            }
        ) {
            hooks += 1
        }
        return hooks
    }

    fun maybePreload(
        context: Context?,
        userId: Long,
        metadata: RestoreMetadata,
        needsChapter: Boolean,
        needsCircle: Boolean,
        needsColumn: Boolean,
        needsBookList: Boolean,
    ) {
        if (context == null || userId <= 0L) return
        if (needsChapter) startPreload(context, userId, SectionKind.CHAPTER, metadata)
        if (needsCircle) startPreload(context, userId, SectionKind.BOOK, metadata)
        if (needsColumn) startPreload(context, userId, SectionKind.COLUMN, metadata)
        if (needsBookList) startPreload(context, userId, SectionKind.BOOKLIST, metadata)
    }

    fun clearUser(userId: Long) {
        SectionKind.values().forEach { kind ->
            val key = PreloadKey(userId, kind)
            preloadInFlight.remove(key)
            preloadCompleted.remove(key)
        }
        synchronized(pendingPreloadCallbacks) {
            val iterator = pendingPreloadCallbacks.entries.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().value.userId == userId) iterator.remove()
            }
        }
    }

    fun clearRequests() {
        preloadInFlight.clear()
        preloadCompleted.clear()
    }

    fun clearCallbacks() {
        synchronized(pendingPreloadCallbacks) {
            pendingPreloadCallbacks.clear()
        }
    }

    private fun startPreload(
        context: Context,
        userId: Long,
        kind: SectionKind,
        metadata: RestoreMetadata,
    ) {
        val key = PreloadKey(userId, kind)
        if (key in preloadCompleted) return
        val request = PreloadRequest(
            id = requestIds.incrementAndGet(),
            userId = userId,
            kind = kind,
            token = lifecycle.capture(userId),
        )
        if (preloadInFlight.putIfAbsent(key, request.id) != null) return
        val callback = createPreloadCallback(request, metadata) ?: run {
            finishPreload(request, success = false)
            FeatureHooks.w("Profile privacy preload skipped: callback unavailable, kind=$kind")
            return
        }
        val method = when (kind) {
            SectionKind.CHAPTER -> metadata.chapterReviewsApi
            SectionKind.BOOK -> metadata.bookReviewsApi
            SectionKind.COLUMN -> metadata.columnsApi
            SectionKind.BOOKLIST -> metadata.bookListApi
        }
        val posted = mainHandler().post {
            if (!isCurrent(request) || preloadInFlight[key] != request.id) {
                synchronized(pendingPreloadCallbacks) { pendingPreloadCallbacks.remove(callback) }
                finishPreload(request, success = false)
                return@post
            }
            val invocation = if (kind == SectionKind.BOOKLIST) {
                FeatureHooks.invokeResult(
                    method,
                    null,
                    context,
                    userId,
                    ProfilePrivacyPreloadPolicy.FIRST_PAGE_INDEX,
                    0L,
                    false,
                    callback,
                )
            } else {
                FeatureHooks.invokeResult(
                    method,
                    null,
                    context,
                    userId,
                    ProfilePrivacyPreloadPolicy.FIRST_PAGE_INDEX,
                    callback,
                )
            }
            if (invocation !is HostAccess.Value) {
                synchronized(pendingPreloadCallbacks) { pendingPreloadCallbacks.remove(callback) }
                finishPreload(request, success = false)
                FeatureHooks.w(
                    "Profile privacy preload request failed: kind=$kind, " +
                        "result=${ProfilePrivacyReflectionSupport.hostAccessLabel(invocation)}",
                )
                return@post
            }
            FeatureHooks.i("Profile privacy preload requested: kind=$kind, userId=<redacted>")
            val timeoutScheduled = mainHandler().postDelayed(
                {
                    val timedOut = synchronized(pendingPreloadCallbacks) {
                        pendingPreloadCallbacks.remove(callback)
                    }
                    if (timedOut?.id == request.id) {
                        finishPreload(request, success = false)
                        FeatureHooks.w(
                            "Profile privacy preload timed out: kind=$kind, userId=<redacted>",
                        )
                    }
                },
                CALLBACK_REQUEST_TIMEOUT_MS,
            )
            if (!timeoutScheduled) {
                synchronized(pendingPreloadCallbacks) { pendingPreloadCallbacks.remove(callback) }
                finishPreload(request, success = false)
                FeatureHooks.w("Profile privacy preload timeout scheduling failed: kind=$kind")
            }
        }
        if (!posted) {
            synchronized(pendingPreloadCallbacks) { pendingPreloadCallbacks.remove(callback) }
            finishPreload(request, success = false)
            FeatureHooks.w("Profile privacy preload dispatch failed: kind=$kind")
        }
    }

    private fun createPreloadCallback(request: PreloadRequest, metadata: RestoreMetadata): Any? {
        val args = metadata.preloadCallbackConstructor.parameterTypes.map { type ->
            when {
                type == metadata.apiClass -> {
                    val constructor = metadata.apiClass.declaredConstructors.firstOrNull { it.parameterTypes.isEmpty() }
                        ?: return null
                    FeatureHooks.newInstance(constructor) ?: return null
                }
                type == metadata.gsonConstructor.declaringClass -> {
                    FeatureHooks.newInstance(metadata.gsonConstructor) ?: return null
                }
                type.isInterface -> {
                    Proxy.newProxyInstance(type.classLoader ?: metadata.apiClass.classLoader, arrayOf(type)) { _, method, _ ->
                        ProfilePrivacyReflectionSupport.defaultProxyValue(method.returnType)
                    }
                }
                else -> return null
            }
        }.toTypedArray()
        val callback = FeatureHooks.newInstance(metadata.preloadCallbackConstructor, *args) ?: return null
        synchronized(pendingPreloadCallbacks) { pendingPreloadCallbacks[callback] = request }
        return callback
    }

    private fun handlePreloadSuccess(request: PreloadRequest, json: JSONObject?, metadata: RestoreMetadata) {
        if (!isCurrent(request)) {
            finishPreload(request, success = false)
            return
        }
        runCatching {
            val result = json?.optInt(HOST_RESULT_KEY, -1) ?: -1
            val data = json?.optJSONObject(HOST_DATA_KEY)
            if (result != 0 || data == null) {
                finishPreload(request, success = false)
                FeatureHooks.w(
                    "Profile privacy preload ignored: kind=${request.kind}, result=$result, hasData=${data != null}",
                )
                return
            }
            val count = when (request.kind) {
                SectionKind.CHAPTER -> data.optInt(COUNT_KEY, 0)
                SectionKind.BOOK -> data.optInt(TOTAL_COUNT_KEY, 0)
                SectionKind.COLUMN -> data.optInt(COUNT_KEY, 0)
                SectionKind.BOOKLIST -> data.optInt(COUNT_KEY, 0)
            }
            val listJson = when (request.kind) {
                SectionKind.CHAPTER -> jsonArrayString(data, CHAPTER_REVIEW_LIST_KEY)
                SectionKind.BOOK -> jsonArrayString(data, BOOK_REVIEW_LIST_KEY)
                SectionKind.COLUMN -> jsonArrayString(data, COLUMN_LIST_KEY)
                SectionKind.BOOKLIST -> jsonArrayString(data, BOOK_LIST_KEY)
            }
            val itemClass = when (request.kind) {
                SectionKind.CHAPTER -> metadata.chapterReviewItemClass
                SectionKind.BOOK -> metadata.circleReviewItemClass
                SectionKind.COLUMN -> metadata.columnItemClass
                SectionKind.BOOKLIST -> metadata.bookListItemClass
            }
            val items = parseJsonArray(listJson, itemClass, metadata)
            val bean = createBeanFromItems(request.kind, count, items, metadata)
            if (bean == null) {
                finishPreload(request, success = count <= 0 && items.isEmpty())
                FeatureHooks.i(
                    "Profile privacy preload empty: kind=${request.kind}, userId=<redacted>, count=$count, items=${items.size}",
                )
                return
            }
            if (!finishPreload(request, success = true)) return
            when (request.kind) {
                SectionKind.CHAPTER -> ProfilePrivacyRestoreData.cachePreloadedSections(
                    request.userId, metadata, bean, null, source = "preload",
                )
                SectionKind.BOOK -> ProfilePrivacyRestoreData.cachePreloadedSections(
                    request.userId, metadata, null, bean, source = "preload",
                )
                SectionKind.COLUMN -> ProfilePrivacyRestoreData.cachePreloadedColumns(request.userId, metadata, bean)
                SectionKind.BOOKLIST -> ProfilePrivacyRestoreData.cachePreloadedBookList(request.userId, metadata, bean)
            }
        }.onFailure { throwable ->
            finishPreload(request, success = false)
            FeatureHooks.e("Profile privacy preload parse failed: kind=${request.kind}", throwable)
        }
    }

    private fun finishPreload(request: PreloadRequest, success: Boolean): Boolean {
        val key = PreloadKey(request.userId, request.kind)
        val removed = preloadInFlight.remove(key, request.id)
        val completed = removed && success && isCurrent(request)
        if (completed) preloadCompleted.add(key)
        return completed
    }

    private fun isCurrent(request: PreloadRequest): Boolean = lifecycle.isCurrent(request.token)

    private fun jsonArrayString(data: JSONObject, key: String): String? {
        data.optJSONArray(key)?.let { return it.toString() }
        val value = data.opt(key) ?: return null
        return when (value) {
            is String -> value
            else -> value.toString()
        }.takeIf { it.isNotBlank() }
    }

    private fun parseJsonArray(json: String?, itemClass: Class<*>, metadata: RestoreMetadata): List<Any?> {
        if (json.isNullOrBlank()) return emptyList()
        val gson = FeatureHooks.newInstance(metadata.gsonConstructor) ?: return emptyList()
        val arrayClass = ReflectArray.newInstance(itemClass, 0).javaClass
        val parsed = FeatureHooks.invoke(metadata.gsonFromJsonClassMethod, gson, json, arrayClass) ?: return emptyList()
        if (!parsed.javaClass.isArray) return emptyList()
        return (0 until ReflectArray.getLength(parsed)).map { index -> ReflectArray.get(parsed, index) }
    }

    private fun createBeanFromItems(
        kind: SectionKind,
        count: Int,
        items: List<*>,
        metadata: RestoreMetadata,
    ): Any? {
        val effectiveCount = if (count > 0) count else items.size
        if (effectiveCount <= 0 || items.isEmpty()) return null
        val copiedItems = ArrayList<Any?>(items)
        return when (kind) {
            SectionKind.CHAPTER -> FeatureHooks.newInstance(metadata.chapterBeanConstructor)?.also {
                FeatureHooks.invoke(metadata.chapterReviewCountSetter, it, effectiveCount)
                FeatureHooks.invoke(metadata.chapterReviewListSetter, it, previewItems(copiedItems))
            }
            SectionKind.BOOK -> FeatureHooks.newInstance(metadata.circleBeanConstructor)?.also {
                FeatureHooks.invoke(metadata.circleReviewCountSetter, it, effectiveCount)
                FeatureHooks.invoke(metadata.circleReviewListSetter, it, previewItems(copiedItems))
            }
            SectionKind.COLUMN -> FeatureHooks.newInstance(metadata.columnBeanConstructor)?.also {
                FeatureHooks.invoke(metadata.columnCountSetter, it, effectiveCount)
                FeatureHooks.invoke(metadata.columnListSetter, it, previewItems(copiedItems))
            }
            SectionKind.BOOKLIST -> FeatureHooks.newInstance(metadata.bookListBeanConstructor)?.also {
                FeatureHooks.invoke(metadata.bookListCountSetter, it, effectiveCount)
                FeatureHooks.invoke(metadata.bookListSetter, it, previewItems(copiedItems))
            }
        }
    }

    private fun previewItems(items: List<*>): ArrayList<Any?> =
        ArrayList(items.take(ProfilePrivacyPreloadPolicy.HOME_PAGE_PREVIEW_LIMIT))

    private data class PreloadKey(val userId: Long, val kind: SectionKind)

    private data class PreloadRequest(
        val id: Long,
        val userId: Long,
        val kind: SectionKind,
        val token: ProfilePrivacyPreloadLifecycle.Token,
    )

    private enum class SectionKind { CHAPTER, BOOK, COLUMN, BOOKLIST }
}
