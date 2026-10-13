package com.xiyunmn.qdhelper.feature.contenttools

import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.host.api.reflection.HostAccess
import java.lang.reflect.Proxy
import java.util.ArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal object ProfilePrivacySupplementalHostAdapter {
    private const val SUPPLEMENTAL_REQUEST_TIMEOUT_MS = 8000L

    fun buildCapsuleBean(sourceItems: List<*>, metadata: RestoreMetadata): Any? {
        val previewLimit = ProfilePrivacyPreloadPolicy.HOME_PAGE_PREVIEW_LIMIT
        val mapped = ArrayList<Any?>(minOf(sourceItems.size, previewLimit))
        sourceItems.take(previewLimit).forEach { sourceItem ->
            if (sourceItem == null || !metadata.capsuleItemClass.isInstance(sourceItem)) return@forEach
            val target = FeatureHooks.newInstance(metadata.capsuleItemConstructor) ?: return@forEach
            FeatureHooks.invoke(
                metadata.capsuleItemTitleSetter,
                target,
                FeatureHooks.invoke(metadata.capsuleItemTitleGetter, sourceItem) as? String,
            )
            FeatureHooks.invoke(
                metadata.capsuleItemContentSetter,
                target,
                FeatureHooks.invoke(metadata.capsuleItemContextGetter, sourceItem) as? String,
            )
            FeatureHooks.invoke(
                metadata.capsuleItemRichContextSetter,
                target,
                FeatureHooks.invoke(metadata.capsuleItemRichContextGetter, sourceItem) as? String,
            )
            FeatureHooks.invoke(
                metadata.capsuleItemDetailH5UrlSetter,
                target,
                FeatureHooks.invoke(metadata.capsuleItemDetailH5UrlGetter, sourceItem) as? String,
            )
            FeatureHooks.invoke(
                metadata.capsuleItemIdSetter,
                target,
                (FeatureHooks.invoke(metadata.capsuleItemIdGetter, sourceItem) as? Number)?.toLong() ?: 0L,
            )
            FeatureHooks.invoke(
                metadata.capsuleItemSourceIdSetter,
                target,
                (FeatureHooks.invoke(metadata.capsuleItemSourceIdGetter, sourceItem) as? Number)?.toLong() ?: 0L,
            )
            val userModule = FeatureHooks.invoke(metadata.capsuleItemUserModuleGetter, sourceItem)
            FeatureHooks.invoke(
                metadata.capsuleItemCreateTimeSetter,
                target,
                (userModule?.let { FeatureHooks.invoke(metadata.userModuleTimeGetter, it) } as? Number)?.toLong() ?: 0L,
            )
            val interaction = FeatureHooks.invoke(metadata.capsuleItemInteractionGetter, sourceItem)
            FeatureHooks.invoke(
                metadata.capsuleItemCommentCountSetter,
                target,
                (interaction?.let { FeatureHooks.invoke(metadata.interactionCommentCountGetter, it) } as? Number)?.toLong()
                    ?: 0L,
            )
            FeatureHooks.invoke(
                metadata.capsuleItemLikeCountSetter,
                target,
                (interaction?.let { FeatureHooks.invoke(metadata.interactionLikeCountGetter, it) } as? Number)?.toLong()
                    ?: 0L,
            )
            FeatureHooks.invoke(
                metadata.capsuleItemLikeStatusSetter,
                target,
                (interaction?.let { FeatureHooks.invoke(metadata.interactionLikeStatusGetter, it) } as? Number)?.toInt()
                    ?: 0,
            )
            mapped += target
        }
        if (mapped.isEmpty()) return null
        return FeatureHooks.newInstance(metadata.capsuleBeanConstructor)?.also { bean ->
            FeatureHooks.invoke(metadata.capsuleListSetter, bean, mapped)
            FeatureHooks.invoke(metadata.capsuleCountSetter, bean, mapped.size)
        }
    }

    fun buildCvBean(payload: Any, metadata: RestoreMetadata): Any? {
        val sourceList = FeatureHooks.invoke(metadata.cvPayloadListGetter, payload) as? List<*> ?: return null
        if (sourceList.isEmpty()) return null
        val preview = ArrayList<Any?>(sourceList.take(ProfilePrivacyPreloadPolicy.HOME_PAGE_PREVIEW_LIMIT))
        return FeatureHooks.newInstance(metadata.anchorInfoBeanConstructor)?.also { bean ->
            FeatureHooks.invoke(metadata.anchorInfoListSetter, bean, preview)
            val total = (FeatureHooks.invoke(metadata.cvPayloadTotalGetter, payload) as? Number)?.toInt()
                ?.takeIf { it > 0 } ?: sourceList.size
            val shared = (FeatureHooks.invoke(metadata.cvPayloadSharedGetter, payload) as? Number)?.toInt() ?: 0
            FeatureHooks.invoke(metadata.anchorInfoTotalSetter, bean, total)
            FeatureHooks.invoke(metadata.anchorInfoSharedSetter, bean, shared)
        }
    }

    fun invokeCvSuspend(api: Any, anchorId: Long, metadata: RestoreMetadata): Any? {
        val parameterTypes = metadata.cvApi.parameterTypes
        val continuationType = parameterTypes.lastOrNull()?.takeIf { it.isInterface } ?: return null
        val latch = CountDownLatch(1)
        val result = AtomicReference<Any?>()
        val failure = AtomicReference<Throwable?>()
        lateinit var continuation: Any
        continuation = Proxy.newProxyInstance(
            continuationType.classLoader ?: metadata.cvApi.declaringClass.classLoader,
            arrayOf(continuationType),
        ) { _, method, args ->
            when (method.name) {
                "resumeWith" -> {
                    val value = args?.firstOrNull()
                    if (isKotlinFailure(value)) {
                        failure.set(value?.let(::kotlinFailureThrowable))
                    } else {
                        result.set(value)
                    }
                    latch.countDown()
                    null
                }
                "getContext" -> emptyCoroutineContext(continuationType.classLoader)
                "toString" -> "qdhelper-cv-continuation"
                "hashCode" -> System.identityHashCode(continuation)
                "equals" -> args?.firstOrNull() === continuation
                else -> ProfilePrivacyReflectionSupport.defaultProxyValue(method.returnType)
            }
        }
        val args = arrayOfNulls<Any>(parameterTypes.size)
        args[0] = "1"
        args[1] = anchorId.toString()
        args[args.lastIndex] = continuation
        val invocation = FeatureHooks.invokeResult(metadata.cvApi, api, *args)
        if (invocation !is HostAccess.Value) {
            FeatureHooks.w(
                "Profile privacy cv invocation failed: " +
                    "result=${ProfilePrivacyReflectionSupport.hostAccessLabel(invocation)}",
            )
            return null
        }
        val immediate = invocation.value ?: return null
        if (!isCoroutineSuspended(immediate)) return immediate
        if (!latch.await(SUPPLEMENTAL_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return null
        failure.get()?.let { return null }
        return result.get()
    }

    fun buildAuthorTalkList(feedItems: List<*>, metadata: RestoreMetadata): ArrayList<Any>? {
        val previewLimit = ProfilePrivacyPreloadPolicy.HOME_PAGE_PREVIEW_LIMIT
        val talks = ArrayList<Any>(previewLimit)
        feedItems.take(previewLimit).forEach { feedItem ->
            val talk = runCatching {
                val talkBean = FeatureHooks.newInstance(metadata.authorTalkListBeanClass.getConstructor())
                    ?: return@runCatching null
                val contentModule = FeatureHooks.invoke(metadata.feedItemContentModuleGetter, feedItem) as? List<*>
                val firstModule = contentModule?.firstOrNull()
                FeatureHooks.invoke(
                    metadata.setContent,
                    talkBean,
                    firstModule?.let { FeatureHooks.invoke(metadata.contentContextGetter, it) as? String },
                )
                FeatureHooks.invoke(
                    metadata.setImageList,
                    talkBean,
                    firstModule?.let { FeatureHooks.invoke(metadata.contentImageListGetter, it) },
                )
                val userModule = FeatureHooks.invoke(metadata.feedItemUserModuleGetter, feedItem)
                FeatureHooks.invoke(
                    metadata.setCreateTime,
                    talkBean,
                    (userModule?.let { FeatureHooks.invoke(metadata.userModuleTimeGetter, it) } as? Number)?.toLong() ?: 0L,
                )
                FeatureHooks.invoke(
                    metadata.setNickName,
                    talkBean,
                    userModule?.let { FeatureHooks.invoke(metadata.userModuleNameGetter, it) as? String },
                )
                FeatureHooks.invoke(
                    metadata.setHeadImage,
                    talkBean,
                    userModule?.let { FeatureHooks.invoke(metadata.userModuleIconGetter, it) as? String },
                )
                val followData = FeatureHooks.invoke(metadata.feedItemFollowDataGetter, feedItem)
                FeatureHooks.invoke(
                    metadata.setTalkId,
                    talkBean,
                    (followData?.let { FeatureHooks.invoke(metadata.followDataIdGetter, it) } as? Number)?.toLong() ?: 0L,
                )
                FeatureHooks.invoke(
                    metadata.setSourceId,
                    talkBean,
                    (followData?.let { FeatureHooks.invoke(metadata.followDataSourceIdGetter, it) } as? Number)?.toLong()
                        ?: 0L,
                )
                val interaction = FeatureHooks.invoke(metadata.feedItemInteractionGetter, feedItem)
                FeatureHooks.invoke(
                    metadata.setLikeCount,
                    talkBean,
                    (interaction?.let { FeatureHooks.invoke(metadata.interactionLikeCountGetter, it) } as? Number)?.toInt()
                        ?: 0,
                )
                FeatureHooks.invoke(
                    metadata.setCommentCount,
                    talkBean,
                    (interaction?.let { FeatureHooks.invoke(metadata.interactionCommentCountGetter, it) } as? Number)?.toInt()
                        ?: 0,
                )
                FeatureHooks.invoke(
                    metadata.setIsLike,
                    talkBean,
                    (interaction?.let { FeatureHooks.invoke(metadata.interactionLikeStatusGetter, it) } as? Number)?.toInt()
                        ?: 0,
                )
                talkBean
            }.getOrNull() ?: return@forEach
            talks += talk
        }
        return talks.takeIf { it.isNotEmpty() }
    }

    private fun isCoroutineSuspended(value: Any): Boolean =
        value.javaClass.name.contains("CoroutineSingletons") || value.toString() == "COROUTINE_SUSPENDED"

    private fun isKotlinFailure(value: Any?): Boolean =
        value?.javaClass?.name?.contains("kotlin.Result") == true &&
            value.javaClass.name.contains("Failure")

    private fun kotlinFailureThrowable(value: Any): Throwable? = runCatching {
        FeatureHooks.allDeclaredFields(value.javaClass)
            .firstOrNull { it.name == "exception" }
            ?.let { field -> FeatureHooks.read(field, value) as? Throwable }
    }.getOrNull()

    private fun emptyCoroutineContext(classLoader: ClassLoader?): Any? = runCatching {
        val loader = classLoader ?: return@runCatching null
        val clazz = FeatureHooks.findClassOrNull("kotlin.coroutines.EmptyCoroutineContext", loader)
            ?: return@runCatching null
        val instance = FeatureHooks.findFieldOrNull(clazz, "INSTANCE") ?: return@runCatching null
        FeatureHooks.read(instance, null)
    }.getOrNull()

}
