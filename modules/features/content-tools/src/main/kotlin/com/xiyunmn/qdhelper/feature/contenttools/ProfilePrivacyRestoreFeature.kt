package com.xiyunmn.qdhelper.feature.contenttools

import android.view.View
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureResources
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import com.xiyunmn.qdhelper.feature.api.runtime.InstallableFeature
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols as QDReaderHost
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object ProfilePrivacyRestoreFeature : InstallableFeature {
    override val name: String = "ProfilePrivacyRestoreFeature"

    private const val USER_HEADER_TYPE = 7
    private const val PERSONAL_BOOK_LIST_TYPE = 8
    private const val PERSONAL_COLUMN_TYPE = 9
    private const val PERSONAL_CHAPTER_COMMENT_TYPE = 10
    private const val PERSONAL_BOOK_COMMENT_TYPE = 11
    private const val PERSONAL_YUELI_TYPE = 13
    private const val PROFILE_MICROBLOG_TYPE = 24
    private const val PERSONAL_AUDIO_TYPE = 18
    private const val PERSONAL_CAPSULE_TYPE = 19
    private const val PERSONAL_CV_BOOK_LIST_TYPE = 21
    private val HEADER_ENTRANCE_NAMES = setOf("layoutCard", "layoutFlower")

    private val responseHookHitCounter = AtomicLong(0L)
    private val inflateHookHitCounter = AtomicLong(0L)
    private val headerClickHookHitCounter = AtomicLong(0L)
    private val cleanupRegistered = AtomicBoolean(false)
    private val sessions get() = profilePrivacyRestoreSessions

    override fun install(
        module: HookModule,
        classLoader: ClassLoader,
        processName: String,
        phase: String,
    ) {
        ensureCleanup()
        if (!QDReaderHost.isMainProcess(processName)) return
        val metadata = RestoreMetadata.resolve(classLoader, processName)
        if (metadata == null) {
            FeatureHooks.w("Profile privacy restore skipped: metadata missing, phase=$phase")
            return
        }

        val responseInstalled = installHomeResponseHook(module, metadata, processName)
        val inflateInstalled = installInflateDataHook(module, metadata)
        val destroyInstalled = installHomePageDestroyHook(module, metadata)
        val headerClickInstalled = installHeaderClickHook(module, metadata)
        val preloadCallbackHooks = ProfilePrivacyPreloader.install(module, classLoader, metadata)
        FeatureHooks.i(
            "Profile privacy restore phase finished: phase=$phase, process=$processName, " +
                "response=$responseInstalled, inflate=$inflateInstalled, destroy=$destroyInstalled, " +
                "headerClick=$headerClickInstalled, preloadCallbackHooks=$preloadCallbackHooks, " +
                "apiOwner=${metadata.apiClass.name}",
        )
    }

    private fun ensureCleanup() {
        if (!cleanupRegistered.compareAndSet(false, true)) return
        FeatureResources.register("ProfilePrivacyRestoreFeature") {
            sessions.clearAll()
            ProfilePrivacyPreloader.clearAll()
            cleanupRegistered.set(false)
        }
    }

    private fun installHeaderClickHook(module: HookModule, metadata: RestoreMetadata): Boolean =
        FeatureHooks.hookAround(
            module = module,
            executable = metadata.personalUserInfoOnClick,
            feature = "profile.privacyRestore:header entrances",
            id = "qdhelper.profile.privacy_restore.header_click",
        ) { chain ->
            val hitCount = headerClickHookHitCounter.incrementAndGet()
            val enabled = SettingsRuntime.isProfilePrivacySecondPageRestoreEnabled(HostSessionStore.contextOrNull())
            val view = chain.args.getOrNull(0) as? View
            val entry = view?.let(::entryResourceName)
            if (hitCount <= 20L || hitCount % 50L == 0L) {
                FeatureHooks.i("Profile privacy header click hit#$hitCount: enabled=$enabled, entry=${entry ?: "other"}")
            }
            if (!enabled || view == null || entry !in HEADER_ENTRANCE_NAMES) {
                return@hookAround chain.proceed()
            }
            val userInfo = findHolderUserInfo(chain.thisObject, metadata)
            if (userInfo == null) return@hookAround chain.proceed()
            val hostUrl = when (entry) {
                "layoutCard" -> FeatureHooks.invoke(metadata.authorLightUrlGetter, userInfo) as? String
                "layoutFlower" -> FeatureHooks.invoke(metadata.allSubUrlGetter, userInfo) as? String
                else -> null
            }
            val url = when (entry) {
                "layoutFlower" -> {
                    val item = findHolderValue(chain.thisObject, metadata.itemClass)
                    val isPrivacyProfile = item?.let(metadata::isPrivacyStatEnabled) == true
                    val userId = item?.let(metadata::userId)
                        ?: (FeatureHooks.invoke(metadata.userInfoUserIdGetter, userInfo) as? Number)?.toLong()
                        ?: 0L
                    ProfilePrivacyHeaderAccess.resolveAllSubUrl(hostUrl, isPrivacyProfile, userId)
                }
                else -> hostUrl?.takeIf(String::isNotBlank)
            } ?: return@hookAround chain.proceed()
            val opened = runCatching {
                FeatureHooks.invoke(metadata.actionUrlProcessMethod, null, view.context, android.net.Uri.parse(url))
                true
            }.onFailure { throwable ->
                FeatureHooks.w("Profile privacy header entrance failed: entry=$entry, error=${throwable.javaClass.name}")
            }.getOrDefault(false)
            if (!opened) return@hookAround chain.proceed()
            FeatureHooks.i("Profile privacy header entrance opened: entry=$entry, urlPresent=true")
            null
        }

    private fun entryResourceName(view: View): String? = runCatching {
        view.resources.getResourceEntryName(view.id)
    }.getOrNull()

    private fun findHolderUserInfo(holder: Any?, metadata: RestoreMetadata): Any? =
        findHolderValue(holder, metadata.userInfoClass)

    private fun findHolderValue(holder: Any?, valueClass: Class<*>): Any? {
        var clazz: Class<*>? = holder?.javaClass
        while (clazz != null) {
            FeatureHooks.allDeclaredFields(clazz).forEach { field ->
                val value = FeatureHooks.read(field, holder)
                if (value != null && valueClass.isInstance(value)) return value
            }
            clazz = clazz.superclass
        }
        return null
    }

    private fun installHomeResponseHook(
        module: HookModule,
        metadata: RestoreMetadata,
        processName: String,
    ): Boolean {
        return FeatureHooks.hookAround(
            module = module,
            executable = metadata.convertMethod,
            feature = "profile.privacyRestore:home response conversion",
            id = "qdhelper.profile.privacy_restore.response_conversion",
        ) { chain ->
            val hitCount = responseHookHitCounter.incrementAndGet()
            val enabled = SettingsRuntime.isProfilePrivacySecondPageRestoreEnabled(HostSessionStore.contextOrNull())
            if (hitCount <= 20L || hitCount % 50L == 0L) {
                FeatureHooks.i(
                    "Profile privacy restore hit#$hitCount: enabled=$enabled, " +
                        "args=${chain.args.map { it?.javaClass?.name ?: "null" }}",
                )
            }
            if (!enabled) {
                return@hookAround chain.proceed()
            }
            val sourceResponse = chain.args.getOrNull(0) ?: return@hookAround chain.proceed()
            val sourceData = FeatureHooks.read(metadata.responseDataField, sourceResponse)
                ?.takeIf(metadata.homePageDataClass::isInstance)
                ?: return@hookAround chain.proceed()
            val converted = chain.proceed() ?: return@hookAround null
            val original = FeatureHooks.read(metadata.responseDataField, converted) as? List<*> ?: return@hookAround converted
            val sourceUserId = ProfilePrivacyRestoreData.resolveUserId(sourceData, original, metadata)
            if (sourceUserId != null && sourceUserId > 0L) {
                val homeTypes = original.mapNotNull { item ->
                    item?.takeIf(metadata.itemClass::isInstance)?.let(metadata::itemType)
                }.distinct().sorted()
                val privacyProfile = original.any { item ->
                    item?.takeIf(metadata.itemClass::isInstance)?.let(metadata::isPrivacyStatEnabled) == true
                }
                // 刻意避开 SafeLog 的 userId=<数字> 脱敏关键字，用 uid= 携带真实 id 供测试定位。
                FeatureHooks.i(
                    "Profile privacy home response debug: uid=$sourceUserId, " +
                        "privacy=$privacyProfile, types=$homeTypes",
                )
            }
            val sourceYueLi = FeatureHooks.invoke(metadata.yueLiGetter, sourceData)
            if (sourceUserId != null && sourceYueLi != null && ProfilePrivacyAdditionalEntries.hasYueLiTips(sourceYueLi)) {
                sessions.cacheYueLi(sourceUserId, sourceYueLi)
                FeatureHooks.i("Profile privacy restore cached yueli summary: userId=<redacted>")
            }
            val sourceBookList = FeatureHooks.invoke(metadata.myBookListGetter, sourceData)
            if (sourceUserId != null && sourceBookList != null && ProfilePrivacyAdditionalEntries.hasBookListData(sourceBookList)) {
                sessions.cacheBookList(sourceUserId, sourceBookList)
            }
            val sourceMicroBlog = FeatureHooks.invoke(metadata.microBlogGetter, sourceData)
            if (sourceUserId != null && sourceMicroBlog != null && ProfilePrivacyAdditionalEntries.hasMicroBlogData(sourceMicroBlog)) {
                sessions.cacheMicroBlog(sourceUserId, sourceMicroBlog)
            }
            val sourceCapsule = FeatureHooks.invoke(metadata.capsuleGetter, sourceData)
            if (sourceUserId != null && sourceCapsule != null && ProfilePrivacyAdditionalEntries.hasCapsuleData(sourceCapsule)) {
                sessions.cacheCapsule(sourceUserId, sourceCapsule)
            }
            val sourceCv = FeatureHooks.invoke(metadata.anchorInfoGetter, sourceData)
            if (sourceUserId != null && sourceCv != null && ProfilePrivacyAdditionalEntries.hasCvData(sourceCv)) {
                sessions.cacheCv(sourceUserId, sourceCv)
            }
            val replacement = runCatching {
                restoreFromHomeResponse(sourceData, original, metadata)
            }.onFailure { throwable ->
                FeatureHooks.e("Profile privacy restore failed", throwable)
            }.getOrNull()
            if (replacement != null) {
                FeatureHooks.write(metadata.responseDataField, converted, replacement)
                FeatureHooks.i("Profile privacy restore updated privacy home entries, process=$processName")
            }
            converted
        }
    }

    private fun installInflateDataHook(
        module: HookModule,
        metadata: RestoreMetadata,
    ): Boolean {
        return FeatureHooks.hookAround(
            module = module,
            executable = metadata.inflateDataMethod,
            feature = "profile.privacyRestore:inflateData",
            id = "qdhelper.profile.privacy_restore.inflate_data",
        ) { chain ->
            val enabled = SettingsRuntime.isProfilePrivacySecondPageRestoreEnabled(HostSessionStore.contextOrNull())
            val list = chain.args.getOrNull(0) as? List<*>
            val hitCount = inflateHookHitCounter.incrementAndGet()
            if (hitCount <= 20L || hitCount % 50L == 0L) {
                FeatureHooks.i(
                    "Profile privacy inflate hit#$hitCount: enabled=$enabled, size=${list?.size ?: "null"}",
                )
            }
            if (!enabled || list == null) return@hookAround chain.proceed()
            val replacement = runCatching {
                restoreAtInflateData(chain.thisObject, list, chain.args.getOrNull(1), metadata)
            }.onFailure { throwable ->
                FeatureHooks.e("Profile privacy inflate restore failed", throwable)
            }.getOrNull()
            if (replacement == null) {
                chain.proceed()
            } else {
                val nextArgs = chain.args.toTypedArray()
                nextArgs[0] = replacement
                chain.proceed(nextArgs)
            }
        }
    }

    private fun restoreFromHomeResponse(
        sourceData: Any,
        original: List<*>,
        metadata: RestoreMetadata,
    ): List<Any?>? {
        val userId = ProfilePrivacyRestoreData.resolveUserId(sourceData, original, metadata) ?: return null
        val rawChapterBean = FeatureHooks.invoke(metadata.chapterReviewGetter, sourceData)
        val rawCircleBean = FeatureHooks.invoke(metadata.circleReviewGetter, sourceData)
        val headerUrlsRestored = restoreHeaderUrls(sourceData, original, metadata)
        val chapterBean = realSectionBean(
            rawChapterBean,
            metadata.chapterReviewListGetter,
            metadata.chapterReviewListSetter,
            metadata.chapterReviewCountSetter,
            metadata.chapterReviewCountGetter,
        )
        val circleBean = realSectionBean(
            rawCircleBean,
            metadata.circleReviewListGetter,
            metadata.circleReviewListSetter,
            metadata.circleReviewCountSetter,
            metadata.circleReviewCountGetter,
        )
        if (chapterBean == null && circleBean == null) {
            val additional = ProfilePrivacyAdditionalEntries.restore(
                sourceData,
                original,
                metadata,
                userId,
                sessions.cachedYueLi(userId),
                sessions.cachedBookList(userId),
                sessions.cachedColumns(userId),
                sessions.cachedMicroBlog(userId),
                sessions.cachedAudio(userId),
                sessions.cachedCapsule(userId),
                sessions.cachedCv(userId),
                resolveAnchorId(null, original, metadata),
            )
            FeatureHooks.i(
                "Profile privacy restore skipped: no recoverable second-page beans, " +
                    "chapter=${sectionDebug(rawChapterBean, metadata.chapterReviewListGetter, metadata.chapterReviewCountGetter)}, " +
                    "book=${sectionDebug(rawCircleBean, metadata.circleReviewListGetter, metadata.circleReviewCountGetter)}",
            )
            return additional ?: original.takeIf { headerUrlsRestored }
        }
        ProfilePrivacyRestoreData.cacheSections(userId, metadata, chapterBean, circleBean, source = "home-response")
        val withAdditional = ProfilePrivacyAdditionalEntries.restore(
            sourceData,
            original,
            metadata,
            userId,
            sessions.cachedYueLi(userId),
            sessions.cachedBookList(userId),
            sessions.cachedColumns(userId),
            sessions.cachedMicroBlog(userId),
            sessions.cachedAudio(userId),
            sessions.cachedCapsule(userId),
            sessions.cachedCv(userId),
            resolveAnchorId(null, original, metadata),
        ) ?: original
        return ProfilePrivacyRestoreData.restorePrivacySecondPageEntries(userId, withAdditional, metadata, chapterBean, circleBean, "response")
            ?: withAdditional.takeIf { it !== original }
            ?: original.takeIf { headerUrlsRestored }
    }

    private fun restoreHeaderUrls(sourceData: Any, original: List<*>, metadata: RestoreMetadata): Boolean {
        if (!original.any { item ->
                item?.takeIf(metadata.itemClass::isInstance)?.let(metadata::isPrivacyStatEnabled) == true
            }) return false
        val sourceUserInfo = FeatureHooks.invoke(metadata.userInfoGetter, sourceData)
            ?.takeIf(metadata.userInfoClass::isInstance) ?: return false
        val sourceAuthor = (FeatureHooks.invoke(metadata.authorLightUrlGetter, sourceUserInfo) as? String)
            ?.takeIf(String::isNotBlank)
        val sourceAllSub = (FeatureHooks.invoke(metadata.allSubUrlGetter, sourceUserInfo) as? String)
            ?.takeIf(String::isNotBlank)
        if (sourceAuthor == null && sourceAllSub == null) return false
        val header = original.firstOrNull { item ->
            item?.takeIf(metadata.itemClass::isInstance)?.let(metadata::itemType) == USER_HEADER_TYPE
        } ?: return false
        val current = FeatureHooks.invoke(metadata.userInfoBeanGetter, header)
            ?.takeIf(metadata.userInfoClass::isInstance)
        val currentAuthor = current?.let { FeatureHooks.invoke(metadata.authorLightUrlGetter, it) as? String }
        val currentAllSub = current?.let { FeatureHooks.invoke(metadata.allSubUrlGetter, it) as? String }
        FeatureHooks.i(
            "Profile privacy header URL probe: sourceAuthor=${sourceAuthor != null}, sourceAllSub=${sourceAllSub != null}, " +
                "currentAuthor=${!currentAuthor.isNullOrBlank()}, currentAllSub=${!currentAllSub.isNullOrBlank()}",
        )
        val needsRestore = (sourceAuthor != null && currentAuthor.isNullOrBlank()) ||
            (sourceAllSub != null && currentAllSub.isNullOrBlank())
        if (!needsRestore) return false
        FeatureHooks.invoke(metadata.userInfoBeanSetter, header, sourceUserInfo)
        FeatureHooks.i(
            "Profile privacy header URLs restored: authorLight=${sourceAuthor != null}, allSub=${sourceAllSub != null}",
        )
        return true
    }

    private fun restoreAtInflateData(
        activity: Any?,
        original: List<*>,
        forceFlag: Any?,
        metadata: RestoreMetadata,
    ): List<Any?>? {
        val userId = ProfilePrivacyRestoreData.resolveUserId(null, original, metadata) ?: return null
        val itemTypes = original.mapNotNull { item ->
            item?.takeIf(metadata.itemClass::isInstance)?.let(metadata::itemType)
        }
        val existingChapter = ProfilePrivacyRestoreData.sectionBeanFromItems(original, metadata, PERSONAL_CHAPTER_COMMENT_TYPE)
            ?.let {
                realSectionBean(
                    it,
                    metadata.chapterReviewListGetter,
                    metadata.chapterReviewListSetter,
                    metadata.chapterReviewCountSetter,
                    metadata.chapterReviewCountGetter,
                )
            }
        val existingCircle = ProfilePrivacyRestoreData.sectionBeanFromItems(original, metadata, PERSONAL_BOOK_COMMENT_TYPE)
            ?.let {
                realSectionBean(
                    it,
                    metadata.circleReviewListGetter,
                    metadata.circleReviewListSetter,
                    metadata.circleReviewCountSetter,
                    metadata.circleReviewCountGetter,
                )
            }
        val hasPrivacyMarker = original.any { item ->
            item?.takeIf(metadata.itemClass::isInstance)?.let(metadata::isPrivacyStatEnabled) == true
        }
        if (!hasPrivacyMarker) {
            ProfilePrivacyRestoreData.cacheSections(userId, metadata, existingChapter, existingCircle, source = "inflate-native")
            return null
        }
        if (activity != null) {
            sessions.activate(userId, activity, original, forceFlag as? Boolean ?: false)
        }
        if (USER_HEADER_TYPE !in itemTypes) return null

        val cached = sessions.cached(userId)
        val needsChapter = PERSONAL_CHAPTER_COMMENT_TYPE !in itemTypes || existingChapter == null
        val needsCircle = PERSONAL_BOOK_COMMENT_TYPE !in itemTypes || existingCircle == null
        val chapterBean = when {
            existingChapter != null -> existingChapter
            cached?.chapterBean != null -> cached.chapterBean
            else -> null
        }
        val circleBean = when {
            existingCircle != null -> existingCircle
            cached?.circleBean != null -> cached.circleBean
            else -> null
        }
        val anchorId = resolveAnchorId(activity, original, metadata)
        ProfilePrivacyPreloader.maybePreload(
            context = (activity as? android.content.Context) ?: HostSessionStore.contextOrNull(),
            userId = userId,
            metadata = metadata,
            needsChapter = needsChapter && cached?.chapterBean == null,
            needsCircle = needsCircle && cached?.circleBean == null,
            needsColumn = PERSONAL_COLUMN_TYPE !in itemTypes && sessions.cachedColumns(userId) == null,
            needsBookList = PERSONAL_BOOK_LIST_TYPE !in itemTypes && sessions.cachedBookList(userId) == null,
            needsMicroBlog = PROFILE_MICROBLOG_TYPE !in itemTypes && sessions.cachedMicroBlog(userId) == null,
            needsAudio = PERSONAL_AUDIO_TYPE !in itemTypes && sessions.cachedAudio(userId) == null,
            needsCapsule = PERSONAL_CAPSULE_TYPE !in itemTypes && sessions.cachedCapsule(userId) == null,
            needsCv = PERSONAL_CV_BOOK_LIST_TYPE !in itemTypes && sessions.cachedCv(userId) == null,
            anchorId = anchorId,
        )
        val source = when {
            cached != null -> "inflate-cache"
            else -> "inflate"
        }
        // 书单/专栏等补充入口在 preload 完成后回填：缓存 Bean 走 AdditionalEntries 补入入口，
        // 再基于补入后的列表恢复 type10/11 二级页入口（与 restoreFromHomeResponse 串联顺序一致）。
        val withAdditional = ProfilePrivacyAdditionalEntries.restore(
            null,
            original,
            metadata,
            userId,
            sessions.cachedYueLi(userId),
            sessions.cachedBookList(userId),
            sessions.cachedColumns(userId),
            sessions.cachedMicroBlog(userId),
            sessions.cachedAudio(userId),
            sessions.cachedCapsule(userId),
            sessions.cachedCv(userId),
            anchorId,
        ) ?: original
        val base = ProfilePrivacyRestoreData.restorePrivacySecondPageEntries(userId, withAdditional, metadata, chapterBean, circleBean, source)
            ?: withAdditional.takeIf { it !== original }
        val yueLiPatched = ProfilePrivacyRestoreData.patchYueLiTipsFromCache(base ?: original, userId, metadata)
        if (yueLiPatched != null && yueLiPatched !== original) return yueLiPatched
        if (sessions.cachedYueLi(userId)?.let(ProfilePrivacyAdditionalEntries::hasYueLiTips) != true) {
            ProfilePrivacyPreloader.maybePreloadYueLiTips(userId, metadata)
        }
        return base
    }

    private fun resolveAnchorId(activity: Any?, original: List<*>, metadata: RestoreMetadata): Long {
        original.asSequence()
            .mapNotNull { item ->
                item?.takeIf(metadata.itemClass::isInstance)
                    ?.let { FeatureHooks.invoke(metadata.anchorIdGetter, it) as? Number }
                    ?.toLong()
            }
            .firstOrNull { it > 0L }
            ?.let { return it }
        return (activity as? android.app.Activity)?.intent?.getLongExtra("AnchorId", 0L) ?: 0L
    }

    private fun installHomePageDestroyHook(
        module: HookModule,
        metadata: RestoreMetadata,
    ): Boolean {
        val method = FeatureHooks.methodsInHierarchy(metadata.inflateDataMethod.declaringClass)
            .firstOrNull { candidate ->
                candidate.name == "onDestroy" && candidate.parameterTypes.isEmpty()
            } ?: return false
        return FeatureHooks.hookAfter(
            module = module,
            executable = method,
            feature = "profile.privacyRestore:homePageDestroy",
            id = "qdhelper.profile.privacy_restore.home_page_destroy",
        ) { chain, _ ->
            clearHomePageCache(chain.thisObject)
        }
    }

    private fun clearHomePageCache(activity: Any?) {
        activity ?: return
        val userIds = sessions.removeActivity(activity)
        userIds.forEach(ProfilePrivacyPreloader::clearUser)
        if (userIds.isNotEmpty()) {
            FeatureHooks.i("Profile privacy restore cleared homepage cache: users=${userIds.size}")
        }
    }

}
