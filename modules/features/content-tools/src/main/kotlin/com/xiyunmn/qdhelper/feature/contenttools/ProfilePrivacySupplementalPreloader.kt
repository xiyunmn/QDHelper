package com.xiyunmn.qdhelper.feature.contenttools

import android.os.Handler
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.hook.concurrency.ManagedExecutor
import com.xiyunmn.qdhelper.shared.io.HttpRequest
import com.xiyunmn.qdhelper.shared.io.HttpTransport
import java.lang.reflect.Modifier
import org.json.JSONObject

internal class ProfilePrivacySupplementalPreloader(
    private val lifecycle: ProfilePrivacyPreloadLifecycle,
    private val mainHandler: () -> Handler,
) {
    private companion object {
        const val YUELI_INDEX_URL = "https://h5.if.qidian.com/majax/ReadExp/Index"
        const val YUELI_H5_BASE_URL =
            "https://h5.if.qidian.com/new/yueli/?_viewmode=0&from=personalpage&userId="
        const val YUELI_REFERER = "https://h5.if.qidian.com/new/yueli/"
        const val YUELI_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Version/4.0 Chrome/120.0.0.0 Mobile Safari/537.36"
        const val YUELI_HTTP_TIMEOUT_MS = 8000
        const val YUELI_MAX_RESPONSE_BYTES = 256L * 1024L

        val YUELI_HEADERS = mapOf(
            "User-Agent" to YUELI_USER_AGENT,
            "Referer" to YUELI_REFERER,
            "Accept" to "application/json, text/plain, */*",
        )
    }

    private val preloads = SupplementalPreloadTracker<PreloadKey>()
    private val worker = ManagedExecutor(
        ownerKey = "feature.content-tools.profile-privacy-preload",
        threadName = "QDHelper-ProfilePreload",
        threadCount = 3,
        onFailure = { throwable -> FeatureHooks.e("Profile privacy preload worker failed", throwable) },
    )

    fun maybePreload(
        userId: Long,
        metadata: RestoreMetadata,
        needsMicroBlog: Boolean,
        needsAudio: Boolean,
        needsCapsule: Boolean,
        needsCv: Boolean,
        anchorId: Long,
    ) {
        if (userId <= 0L) return
        if (needsMicroBlog) maybePreloadUserFollow(userId, metadata)
        if (needsAudio) maybePreloadAudio(userId, metadata)
        if (needsCapsule) maybePreloadCapsule(userId, metadata)
        if (needsCv && anchorId > 0L) maybePreloadCv(userId, anchorId, metadata)
    }

    // 隐私用户主页的「TA的阅历」天数被服务端剥离，但 H5 无鉴权接口
    // /majax/ReadExp/Index 按 userId 直接返回 expInfo.days，构造 tips 回填。
    fun maybePreloadYueLiTips(userId: Long, metadata: RestoreMetadata) {
        if (userId <= 0L) return
        val key = PreloadKey(userId, Kind.YUELI)
        launch(key) { token ->
            val json = fetchYueLiIndex(userId)
            val days = json?.optJSONObject("data")?.optJSONObject("expInfo")?.optInt("days", -1)
            if (days == null || days <= 0) {
                FeatureHooks.w("Profile privacy yueLi tips preload empty: userId=<redacted>, days=$days")
                return@launch false
            }
            val bean = createYueLiTipsBean(userId, days, metadata)
            if (bean == null) {
                FeatureHooks.w("Profile privacy yueLi tips bean unavailable: userId=<redacted>")
                return@launch false
            }
            FeatureHooks.i("Profile privacy yueLi tips preload ok: userId=<redacted>, days=$days")
            postResult(token) {
                ProfilePrivacyRestoreData.cacheYueLiTips(userId, metadata, bean)
            }
        }
    }

    fun clearUser(userId: Long) {
        preloads.clearWhere { key -> key.userId == userId }
    }

    fun clearAll() {
        preloads.clearAll()
        worker.close()
    }

    // 动态（微博）主动请求：db.w.b (getByUser) 返回 QDUserFollowBean，把 FeedItems 映射
    // 成 HomePageMicroBlogBean.AuthorTalkListBean 回填原生个人主页的 type24 卡片。
    private fun maybePreloadUserFollow(userId: Long, metadata: RestoreMetadata) {
        if (userId <= 0L) return
        val key = PreloadKey(userId, Kind.MICROBLOG)
        launch(key) { token ->
            val api = metadata.retrofitGetApiMethod?.let {
                FeatureHooks.invoke(it, metadata.retrofitInstance, metadata.userFollowApiClass)
            } ?: return@launch false
            val call = FeatureHooks.invoke(
                metadata.userFollowApi,
                api,
                userId,
                ProfilePrivacyPreloadPolicy.FIRST_PAGE_INDEX,
                ProfilePrivacyPreloadPolicy.HOME_PAGE_PREVIEW_LIMIT,
            ) ?: return@launch false
            val executeMethod = call.javaClass.methods.firstOrNull {
                it.name == "execute" && it.parameterTypes.isEmpty()
            } ?: return@launch false
            val response = FeatureHooks.invoke(executeMethod, call) ?: return@launch false
            val bodyMethod = response.javaClass.methods.firstOrNull {
                it.name == "body" && it.parameterTypes.isEmpty()
            } ?: return@launch false
            val serverBody = FeatureHooks.invoke(bodyMethod, response) ?: return@launch false
            val feedItems: List<*> = FeatureHooks.read(metadata.responseDataField, serverBody)
                ?.let { FeatureHooks.invoke(metadata.getFeedItems, it) } as? List<*> ?: emptyList<Any?>()
            if (feedItems.isEmpty()) return@launch true
            val talks = ProfilePrivacySupplementalHostAdapter.buildAuthorTalkList(feedItems, metadata)
                ?: return@launch false
            val bean = FeatureHooks.newInstance(metadata.microBlogBeanClass.getConstructor())
                ?: return@launch false
            FeatureHooks.invoke(metadata.setAuthorTalkList, bean, talks)
            FeatureHooks.invoke(metadata.microBlogCountSetter, bean, talks.size)
            FeatureHooks.i("Profile privacy microBlog preload ok: userId=<redacted>, talks=${talks.size}")
            postResult(token) {
                ProfilePrivacyRestoreData.cachePreloadedMicroBlog(userId, metadata, bean)
            }
        }
    }

    // 配音主动请求按 Retrofit path 定位，返回 Observable<ServerResponse<AudioInfoBean>>；
    // 通过四版本一致的 RxJava 公共 API blockingFirst() 同步拿首项并回填 type18 卡片。
    private fun maybePreloadAudio(userId: Long, metadata: RestoreMetadata) {
        if (userId <= 0L) return
        val key = PreloadKey(userId, Kind.AUDIO)
        launch(key) { token ->
            val api = metadata.retrofitGetApiMethod?.let {
                FeatureHooks.invoke(it, metadata.retrofitInstance, metadata.userAudioListApiClass)
            } ?: return@launch false
            val observable = FeatureHooks.invoke(
                metadata.userAudioListApi,
                api,
                userId,
                ProfilePrivacyPreloadPolicy.FIRST_PAGE_INDEX,
                ProfilePrivacyPreloadPolicy.HOME_PAGE_PREVIEW_LIMIT,
            ) ?: return@launch false
            val serverBody = FeatureHooks.invoke(metadata.audioBlockingFirstMethod, observable)
                ?: return@launch false
            val audioBean = FeatureHooks.read(metadata.responseDataField, serverBody)
                ?.takeIf(metadata.audioBeanClass::isInstance) ?: return@launch false
            if (!ProfilePrivacyAdditionalEntries.hasAudioData(audioBean)) return@launch true
            FeatureHooks.i("Profile privacy audio preload ok: userId=<redacted>")
            postResult(token) {
                ProfilePrivacyRestoreData.cachePreloadedAudio(userId, metadata, audioBean)
            }
        }
    }

    // 胶囊主动请求：/argus/api/v1/capsulenovel/createByUser 返回
    // Observable<ServerResponse<MyCollectionCapsuleEntity>>。
    private fun maybePreloadCapsule(userId: Long, metadata: RestoreMetadata) {
        if (userId <= 0L) return
        val key = PreloadKey(userId, Kind.CAPSULE)
        launch(key) { token ->
            val api = metadata.retrofitGetApiMethod?.let {
                FeatureHooks.invoke(it, metadata.retrofitInstance, metadata.capsuleApi.declaringClass)
            } ?: return@launch false
            val observable = FeatureHooks.invoke(
                metadata.capsuleApi,
                api,
                userId,
                ProfilePrivacyPreloadPolicy.FIRST_PAGE_INDEX,
                ProfilePrivacyPreloadPolicy.HOME_PAGE_PREVIEW_LIMIT,
            ) ?: return@launch false
            val blockingFirst = observable.javaClass.methods.firstOrNull {
                Modifier.isPublic(it.modifiers) &&
                    !Modifier.isStatic(it.modifiers) &&
                    it.name == "blockingFirst" &&
                    it.parameterTypes.isEmpty()
            } ?: metadata.capsuleApi.returnType.methods.firstOrNull {
                Modifier.isPublic(it.modifiers) &&
                    !Modifier.isStatic(it.modifiers) &&
                    it.name == "blockingFirst" &&
                    it.parameterTypes.isEmpty()
            } ?: return@launch false
            val serverBody = FeatureHooks.invoke(blockingFirst, observable) ?: return@launch false
            val entity = FeatureHooks.read(metadata.responseDataField, serverBody)
                ?.takeIf(metadata.capsuleEntityClass::isInstance) ?: return@launch false
            val sourceItems = FeatureHooks.read(metadata.capsuleEntityListField, entity) as? List<*>
                ?: return@launch false
            if (sourceItems.isEmpty()) return@launch true
            val bean = ProfilePrivacySupplementalHostAdapter.buildCapsuleBean(sourceItems, metadata)
                ?: return@launch false
            FeatureHooks.i(
                "Profile privacy capsule preload ok: userId=<redacted>, items=${sourceItems.size}",
            )
            postResult(token) {
                ProfilePrivacyRestoreData.cachePreloadedCapsule(userId, metadata, bean)
            }
        }
    }

    // CV/配音作品接口是 Kotlin suspend 方法，在后台线程等待 resumeWith。
    private fun maybePreloadCv(userId: Long, anchorId: Long, metadata: RestoreMetadata) {
        if (userId <= 0L || anchorId <= 0L) return
        val key = PreloadKey(userId, Kind.CV, anchorId)
        launch(key) { token ->
            val api = metadata.retrofitGetApiMethod?.let {
                FeatureHooks.invoke(it, metadata.retrofitInstance, metadata.cvApi.declaringClass)
            } ?: return@launch false
            val response = ProfilePrivacySupplementalHostAdapter.invokeCvSuspend(api, anchorId, metadata)
                ?: return@launch false
            val payload = FeatureHooks.read(metadata.responseDataField, response)
                ?.takeIf(metadata.cvPayloadClass::isInstance) ?: return@launch false
            val bean = ProfilePrivacySupplementalHostAdapter.buildCvBean(payload, metadata)
                ?: return@launch false
            FeatureHooks.i("Profile privacy cv preload ok: userId=<redacted>, anchorId=$anchorId")
            postResult(token) {
                ProfilePrivacyRestoreData.cachePreloadedCv(userId, metadata, bean)
            }
        }
    }

    private fun fetchYueLiIndex(userId: Long): JSONObject? = runCatching {
        val response = HttpTransport.execute(
            HttpRequest(
                url = "$YUELI_INDEX_URL?userId=$userId",
                maxResponseBytes = YUELI_MAX_RESPONSE_BYTES,
                connectTimeoutMs = YUELI_HTTP_TIMEOUT_MS,
                readTimeoutMs = YUELI_HTTP_TIMEOUT_MS,
                headers = YUELI_HEADERS,
            ),
        )
        JSONObject(String(response.body, Charsets.UTF_8))
    }.getOrNull()

    private fun createYueLiTipsBean(userId: Long, days: Int, metadata: RestoreMetadata): Any? {
        val bean = FeatureHooks.newInstance(metadata.yueLiConstructor) ?: return null
        FeatureHooks.invoke(metadata.setYueLiUrl, bean, "$YUELI_H5_BASE_URL$userId")
        FeatureHooks.invoke(metadata.setYueLiTips, bean, "来到起点已经 $days 天")
        return bean
    }

    private fun launch(
        key: PreloadKey,
        block: (ProfilePrivacyPreloadLifecycle.Token) -> Boolean,
    ) {
        val attempt = preloads.tryStart(key) ?: return
        val token = lifecycle.capture(key.userId)
        runCatching {
            worker.execute {
                val delivered = runCatching { block(token) }
                    .onFailure { throwable ->
                        FeatureHooks.w(
                            "Profile privacy supplemental preload failed: kind=${key.kind}, " +
                                "userId=<redacted>, error=${throwable.javaClass.simpleName}",
                        )
                    }
                    .getOrDefault(false)
                val completed = delivered && lifecycle.isCurrent(token)
                preloads.finish(attempt, completed)
                if (!completed && lifecycle.isCurrent(token)) {
                    FeatureHooks.w(
                        "Profile privacy supplemental preload incomplete: kind=${key.kind}, userId=<redacted>",
                    )
                }
            }
        }.onSuccess { accepted ->
            if (!accepted) preloads.finish(attempt, success = false)
        }.onFailure { throwable ->
            preloads.finish(attempt, success = false)
            FeatureHooks.w(
                "Profile privacy supplemental preload dispatch failed: kind=${key.kind}, " +
                    "userId=<redacted>, error=${throwable.javaClass.simpleName}",
            )
        }
    }

    private fun postResult(
        token: ProfilePrivacyPreloadLifecycle.Token,
        action: () -> Unit,
    ): Boolean = mainHandler().post {
        if (lifecycle.isCurrent(token)) action()
    }

    private data class PreloadKey(
        val userId: Long,
        val kind: Kind,
        val variantId: Long = 0L,
    )

    private enum class Kind { YUELI, MICROBLOG, AUDIO, CAPSULE, CV }
}
