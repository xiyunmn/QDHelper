package com.xiyunmn.qdhelper.feature.contenttools

import android.os.Handler
import android.os.Looper
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import java.util.ArrayList

// ProfilePrivacyRestoreFeature 与 preload 回填共享的会话缓存与主线程调度。
// ProfilePrivacySessionStore 是普通类而非单例，因此在此提升为顶层共享实例。
internal val profilePrivacyRestoreSessions by lazy { ProfilePrivacySessionStore() }

private val profilePrivacyMainHandler by lazy { Handler(Looper.getMainLooper()) }

internal object ProfilePrivacyRestoreData {
    private const val USER_HEADER_TYPE = 7
    private const val PERSONAL_CHAPTER_COMMENT_TYPE = 10
    private const val PERSONAL_BOOK_COMMENT_TYPE = 11
    private const val PERSONAL_YUELI_TYPE = 13
    private const val PROFILE_MICROBLOG_TYPE = 24

    private val sessions get() = profilePrivacyRestoreSessions
    private val mainHandler get() = profilePrivacyMainHandler

    fun patchYueLiTipsFromCache(base: List<*>, userId: Long, metadata: RestoreMetadata): List<Any?>? {
        val cached = sessions.cachedYueLi(userId) ?: return null
        if (!ProfilePrivacyAdditionalEntries.hasYueLiTips(cached)) return null
        val patched = ArrayList<Any?>(base)
        var changed = false
        patched.forEachIndexed { _, item ->
            val type = item?.takeIf(metadata.itemClass::isInstance)?.let(metadata::itemType)
            if (type != PERSONAL_YUELI_TYPE) return@forEachIndexed
            val current = item?.let(::invokeYueLiBean)
            if (current != null && ProfilePrivacyAdditionalEntries.hasYueLiTips(current)) return@forEachIndexed
            if (current != null) {
                FeatureHooks.invoke(metadata.setYueLiTips, current, invokeYueLiTips(cached))
            } else if (metadata.setYueLiBean != null) {
                FeatureHooks.invoke(metadata.setYueLiBean, item, cached)
            } else {
                return@forEachIndexed
            }
            changed = true
        }
        return patched.takeIf { changed }
    }

    private fun invokeYueLiBean(item: Any): Any? = invokeNoArg(item, "getYueLiBean")

    private fun invokeYueLiTips(bean: Any): String? = invokeNoArg(bean, "getTips") as? String

    private fun invokeNoArg(owner: Any, name: String): Any? =
        FeatureHooks.invokeNoArgOrNull(owner, name)

    // 阅历天数预取完成后回填：写入带 tips 的缓存并让当前主页重新 inflate。
    fun cacheYueLiTips(userId: Long, metadata: RestoreMetadata, tipsBean: Any?) {
        if (userId <= 0L || tipsBean == null) return
        sessions.cacheYueLi(userId, tipsBean)
        val active = sessions.active(userId) ?: return
        val activity = active.activityRef.get() ?: return
        mainHandler.post {
            runCatching {
                FeatureHooks.invoke(metadata.inflateDataMethod, activity, active.original, active.forceFlag)
            }.onFailure { throwable ->
                FeatureHooks.e("Profile privacy yueLi tips refresh failed", throwable)
            }
        }
    }

    fun restorePrivacySecondPageEntries(
        userId: Long,
        original: List<*>,
        metadata: RestoreMetadata,
        chapterBean: Any?,
        circleBean: Any?,
        source: String,
    ): List<Any?>? {
        if (original.isEmpty()) {
            FeatureHooks.i("Profile privacy restore skipped: empty homepage item list")
            return null
        }
        val itemTypes = original.mapNotNull { item ->
            item?.takeIf(metadata.itemClass::isInstance)?.let(metadata::itemType)
        }
        if (USER_HEADER_TYPE !in itemTypes) {
            FeatureHooks.i("Profile privacy restore skipped: user header missing, itemTypes=$itemTypes")
            return null
        }
        val hasPrivacyMarker = original.any { item ->
            item?.takeIf(metadata.itemClass::isInstance)?.let(metadata::isPrivacyStatEnabled) == true
        }
        if (!hasPrivacyMarker) {
            FeatureHooks.i("Profile privacy restore skipped: privacy marker missing")
            return null
        }
        if (chapterBean == null && circleBean == null) {
            FeatureHooks.i("Profile privacy restore skipped: no second-page entrance beans, source=$source")
            return null
        }

        val chapterIndex = indexOfType(original, metadata, PERSONAL_CHAPTER_COMMENT_TYPE)
        val circleIndex = indexOfType(original, metadata, PERSONAL_BOOK_COMMENT_TYPE)
        val restored = ArrayList<Any?>(original.size + 2)
        var inserted = false
        original.forEach { item ->
            restored += item
            val type = item?.takeIf(metadata.itemClass::isInstance)?.let(metadata::itemType)
            if (!inserted && type == USER_HEADER_TYPE) {
                if (chapterIndex < 0 && chapterBean != null) {
                    restored += createEntry(PERSONAL_CHAPTER_COMMENT_TYPE, userId, chapterBean, metadata) ?: return null
                }
                if (circleIndex < 0 && circleBean != null) {
                    restored += createEntry(PERSONAL_BOOK_COMMENT_TYPE, userId, circleBean, metadata) ?: return null
                }
                inserted = true
            }
        }
        if (!inserted) return null

        var changed = (chapterIndex < 0 && chapterBean != null) || (circleIndex < 0 && circleBean != null)
        if (chapterIndex >= 0 && chapterBean != null) {
            val chapterItem = restored.firstOrNull { item ->
                item?.takeIf(metadata.itemClass::isInstance)?.let(metadata::itemType) == PERSONAL_CHAPTER_COMMENT_TYPE
            } ?: return null
            if (!applyEntry(chapterItem, PERSONAL_CHAPTER_COMMENT_TYPE, userId, chapterBean, metadata)) return null
            changed = true
        }
        if (circleIndex >= 0 && circleBean != null) {
            val circleItem = restored.firstOrNull { item ->
                item?.takeIf(metadata.itemClass::isInstance)?.let(metadata::itemType) == PERSONAL_BOOK_COMMENT_TYPE
            } ?: return null
            if (!applyEntry(circleItem, PERSONAL_BOOK_COMMENT_TYPE, userId, circleBean, metadata)) return null
            changed = true
        }
        if (repositionMicroBlogAfterHeader(restored, metadata)) changed = true
        return restored.takeIf { changed }?.also {
            FeatureHooks.i(
                "Profile privacy restore built replacement: source=$source, userId=<redacted>, " +
                    "size=${it.size}, chapter=${chapterBean != null}, book=${circleBean != null}",
            )
        }
    }

    // 动态(24) 紧跟用户信息 header：本章说(10)/书评(11) 补入时会插到 header 后把动态
    // 顶到后面，此处单独把动态挪回 header 后首位，其余卡片相对顺序保持不变。
    private fun repositionMicroBlogAfterHeader(list: MutableList<Any?>, metadata: RestoreMetadata): Boolean {
        val headerIndex = list.indexOfFirst { item ->
            item?.takeIf(metadata.itemClass::isInstance)?.let(metadata::itemType) == USER_HEADER_TYPE
        }
        val microBlogIndex = list.indexOfFirst { item ->
            item?.takeIf(metadata.itemClass::isInstance)?.let(metadata::itemType) == PROFILE_MICROBLOG_TYPE
        }
        if (headerIndex < 0 || microBlogIndex <= headerIndex + 1) return false
        val microBlog = list.removeAt(microBlogIndex)
        list.add(headerIndex + 1, microBlog)
        return true
    }

    fun resolveUserId(sourceData: Any?, original: List<*>, metadata: RestoreMetadata): Long? {
        original.asSequence()
            .mapNotNull { item ->
                item?.takeIf(metadata.itemClass::isInstance)?.let { metadata.userId(it) }
            }
            .firstOrNull { it > 0L }
            ?.let { return it }

        sourceData ?: return null
        val userInfo = FeatureHooks.invoke(metadata.userInfoGetter, sourceData)
        val userInfoId = userInfo?.let { FeatureHooks.invoke(metadata.userInfoUserIdGetter, it) as? Number }?.toLong()
        if (userInfoId != null && userInfoId > 0L) return userInfoId

        val authorInfo = FeatureHooks.invoke(metadata.authorInfoGetter, sourceData)
        val authorInfoId = authorInfo?.let { FeatureHooks.invoke(metadata.authorInfoIdGetter, it) as? Number }?.toLong()
        if (authorInfoId != null && authorInfoId > 0L) return authorInfoId

        return null
    }

    private fun indexOfType(original: List<*>, metadata: RestoreMetadata, itemType: Int): Int {
        return original.indexOfFirst { item ->
            item?.takeIf(metadata.itemClass::isInstance)?.let(metadata::itemType) == itemType
        }
    }

    fun sectionBeanFromItems(original: List<*>, metadata: RestoreMetadata, itemType: Int): Any? {
        val item = original.firstOrNull { candidate ->
            candidate?.takeIf(metadata.itemClass::isInstance)?.let(metadata::itemType) == itemType
        } ?: return null
        return when (itemType) {
            PERSONAL_CHAPTER_COMMENT_TYPE -> FeatureHooks.invoke(metadata.getChapterBean, item)
            PERSONAL_BOOK_COMMENT_TYPE -> FeatureHooks.invoke(metadata.getCircleBean, item)
            else -> null
        }
    }

    private fun createEntry(
        type: Int,
        userId: Long,
        bean: Any,
        metadata: RestoreMetadata,
    ): Any? {
        val item = FeatureHooks.newInstance(metadata.itemConstructor, type) ?: return null
        return if (applyEntry(item, type, userId, bean, metadata)) item else null
    }

    private fun applyEntry(
        item: Any?,
        type: Int,
        userId: Long,
        bean: Any,
        metadata: RestoreMetadata,
    ): Boolean {
        item?.takeIf(metadata.itemClass::isInstance) ?: return false
        FeatureHooks.invoke(metadata.setUserId, item, userId)
        FeatureHooks.invoke(metadata.setMaster, item, false)
        FeatureHooks.invoke(metadata.setAuthor, item, false)
        FeatureHooks.invoke(metadata.setPrivacy, item, true)
        return when (type) {
            PERSONAL_CHAPTER_COMMENT_TYPE -> {
                FeatureHooks.invoke(metadata.setChapterBean, item, bean)
                true
            }
            PERSONAL_BOOK_COMMENT_TYPE -> {
                FeatureHooks.invoke(metadata.setCircleBean, item, bean)
                true
            }
            else -> false
        }
    }

    fun cacheSections(
        userId: Long,
        metadata: RestoreMetadata,
        chapterBean: Any?,
        circleBean: Any?,
        source: String,
        requireActivePage: Boolean = false,
    ) {
        if (userId <= 0L || (chapterBean == null && circleBean == null)) return
        val normalizedChapter = realSectionBean(
            chapterBean,
            metadata.chapterReviewListGetter,
            metadata.chapterReviewListSetter,
            metadata.chapterReviewCountSetter,
            metadata.chapterReviewCountGetter,
        )
        val normalizedCircle = realSectionBean(
            circleBean,
            metadata.circleReviewListGetter,
            metadata.circleReviewListSetter,
            metadata.circleReviewCountSetter,
            metadata.circleReviewCountGetter,
        )
        if (normalizedChapter == null && normalizedCircle == null) return
        if (!sessions.cache(userId, normalizedChapter, normalizedCircle, requireActivePage)) return
        FeatureHooks.i(
            "Profile privacy restore cached sections: source=$source, userId=<redacted>, " +
                "chapter=${normalizedChapter?.let { sectionDebug(it, metadata.chapterReviewListGetter, metadata.chapterReviewCountGetter) }}, " +
                "book=${normalizedCircle?.let { sectionDebug(it, metadata.circleReviewListGetter, metadata.circleReviewCountGetter) }}",
        )
        refreshActiveHomePage(userId, metadata)
    }

    fun cachePreloadedSections(
        userId: Long,
        metadata: RestoreMetadata,
        chapterBean: Any?,
        circleBean: Any?,
        source: String,
    ) {
        cacheSections(
            userId,
            metadata,
            chapterBean,
            circleBean,
            source,
            requireActivePage = true,
        )
    }

    // 书单 preload 完成回填：缓存 Bean 后让当前主页重新 inflate，
    // restoreAtInflateData 会经 AdditionalEntries 补入 type8 入口。
    fun cachePreloadedBookList(userId: Long, metadata: RestoreMetadata, bean: Any?) {
        if (userId <= 0L || bean == null) return
        sessions.cacheBookList(userId, bean)
        refreshSupplementalHomePage(userId, metadata, "bookList")
    }

    // 专栏 preload 完成回填：缓存 Bean 后让当前主页重新 inflate，
    // restoreAtInflateData 会经 AdditionalEntries 补入 type9 入口。
    fun cachePreloadedColumns(userId: Long, metadata: RestoreMetadata, bean: Any?) {
        if (userId <= 0L || bean == null) return
        sessions.cacheColumns(userId, bean)
        refreshSupplementalHomePage(userId, metadata, "columns")
    }

    // 动态 preload 完成回填：缓存 Bean 后让当前主页重新 inflate，
    // restoreAtInflateData 会经 AdditionalEntries 补入原生个人主页使用的 type24 入口。
    fun cachePreloadedMicroBlog(userId: Long, metadata: RestoreMetadata, bean: Any?) {
        if (userId <= 0L || bean == null) return
        sessions.cacheMicroBlog(userId, bean)
        refreshSupplementalHomePage(userId, metadata, "microBlog")
    }

    // 配音 preload 完成回填：缓存 Bean 后让当前主页重新 inflate，
    // restoreAtInflateData 会经 AdditionalEntries 补入 type18 入口。
    fun cachePreloadedAudio(userId: Long, metadata: RestoreMetadata, bean: Any?) {
        if (userId <= 0L || bean == null) return
        sessions.cacheAudio(userId, bean)
        refreshSupplementalHomePage(userId, metadata, "audio")
    }

    // 胶囊 preload 完成回填：缓存 CapsuleBean 后由 AdditionalEntries 补入 type19。
    fun cachePreloadedCapsule(userId: Long, metadata: RestoreMetadata, bean: Any?) {
        if (userId <= 0L || bean == null) return
        sessions.cacheCapsule(userId, bean)
        refreshSupplementalHomePage(userId, metadata, "capsule")
    }

    // CV/配音作品 preload 完成回填：缓存 AnchorInfoBean 后由 AdditionalEntries 补入 type21。
    fun cachePreloadedCv(userId: Long, metadata: RestoreMetadata, bean: Any?) {
        if (userId <= 0L || bean == null) return
        sessions.cacheCv(userId, bean)
        refreshSupplementalHomePage(userId, metadata, "cv")
    }

    private fun refreshSupplementalHomePage(userId: Long, metadata: RestoreMetadata, kind: String) {
        val active = sessions.active(userId) ?: return
        val activity = active.activityRef.get() ?: return
        mainHandler.post {
            runCatching {
                FeatureHooks.invoke(metadata.inflateDataMethod, activity, active.original, active.forceFlag)
            }.onFailure { throwable ->
                FeatureHooks.e("Profile privacy $kind preload refresh failed", throwable)
            }
        }
    }

    private fun refreshActiveHomePage(userId: Long, metadata: RestoreMetadata) {
        val active = sessions.active(userId) ?: return
        val activity = active.activityRef.get() ?: run {
            return
        }
        val cached = sessions.cached(userId) ?: return
        val replacement = restorePrivacySecondPageEntries(
            userId = userId,
            original = active.original,
            metadata = metadata,
            chapterBean = cached.chapterBean,
            circleBean = cached.circleBean,
            source = "active-refresh",
        ) ?: return
        mainHandler.post {
            runCatching {
                FeatureHooks.invoke(metadata.inflateDataMethod, activity, replacement, active.forceFlag)
            }.onFailure { throwable ->
                FeatureHooks.e("Profile privacy active homepage refresh failed", throwable)
            }
        }
    }
}
