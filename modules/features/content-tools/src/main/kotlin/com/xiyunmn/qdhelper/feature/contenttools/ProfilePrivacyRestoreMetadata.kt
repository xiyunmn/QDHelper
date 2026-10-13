package com.xiyunmn.qdhelper.feature.contenttools

import android.content.Context
import android.net.Uri
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.host.api.target.ContentToolsTargetKeys
import com.xiyunmn.qdhelper.host.api.target.HostTargetResolver
import com.xiyunmn.qdhelper.host.api.target.TargetKey
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols as QDReaderHost
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.GenericArrayType
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable
import java.lang.reflect.WildcardType

internal data class RestoreMetadata(
    val apiClass: Class<*>,
    val convertMethod: Method,
    val chapterReviewsApi: Method,
    val bookReviewsApi: Method,
    val preloadCallbackConstructor: Constructor<*>,
    val preloadCallbackSuccessMethod: Method,
    val preloadCallbackErrorMethod: Method?,
    val gsonConstructor: Constructor<*>,
    val gsonFromJsonClassMethod: Method,
    val inflateDataMethod: Method,
    val responseDataField: Field,
    val homePageDataClass: Class<*>,
    val itemClass: Class<*>,
    val chapterReviewItemClass: Class<*>,
    val circleReviewItemClass: Class<*>,
    val itemConstructor: Constructor<*>,
    val chapterBeanConstructor: Constructor<*>,
    val circleBeanConstructor: Constructor<*>,
    val itemTypeGetter: Method,
    val privacyGetter: Method,
    val userIdGetter: Method,
    val getChapterBean: Method,
    val getCircleBean: Method,
    val chapterReviewGetter: Method,
    val circleReviewGetter: Method,
    val chapterReviewListGetter: Method,
    val circleReviewListGetter: Method,
    val chapterReviewListSetter: Method,
    val circleReviewListSetter: Method,
    val chapterReviewCountGetter: Method,
    val circleReviewCountGetter: Method,
    val chapterReviewCountSetter: Method,
    val circleReviewCountSetter: Method,
    val userInfoGetter: Method,
    val userInfoUserIdGetter: Method,
    val authorInfoGetter: Method,
    val authorInfoIdGetter: Method,
    val setUserId: Method,
    val setMaster: Method,
    val setAuthor: Method,
    val setPrivacy: Method,
    val setChapterBean: Method,
    val setCircleBean: Method,
    val userInfoBeanGetter: Method,
    val userInfoBeanSetter: Method,
    val userInfoClass: Class<*>,
    val authorLightUrlGetter: Method,
    val allSubUrlGetter: Method,
    val myBookListGetter: Method,
    val yueLiGetter: Method,
    val yueLiConstructor: Constructor<*>,
    val setYueLiUrl: Method,
    val setYueLiTips: Method,
    val microBlogGetter: Method,
    val setMyBookListBean: Method?,
    val setYueLiBean: Method?,
    val setYueLiStatus: Method?,
    val setMicroBlogBean: Method?,
    val columnsApi: Method,
    val bookListApi: Method,
    val capsuleApi: Method,
    val cvApi: Method,
    val columnGetter: Method,
    val setColumnBean: Method?,
    val columnBeanConstructor: Constructor<*>,
    val columnListGetter: Method,
    val columnListSetter: Method,
    val columnCountSetter: Method,
    val columnItemClass: Class<*>,
    val bookListBeanConstructor: Constructor<*>,
    val bookListSetter: Method,
    val bookListCountSetter: Method,
    val bookListItemClass: Class<*>,
    val userFollowApi: Method,
    val userFollowApiClass: Class<*>,
    val userAudioListApi: Method,
    val userAudioListApiClass: Class<*>,
    val audioBlockingFirstMethod: Method,
    val retrofitInstance: Any?,
    val retrofitGetApiMethod: Method?,
    val getFeedItems: Method,
    val feedItemContentModuleGetter: Method,
    val feedItemFollowDataGetter: Method,
    val feedItemUserModuleGetter: Method,
    val feedItemInteractionGetter: Method,
    val contentContextGetter: Method,
    val contentImageListGetter: Method,
    val userModuleTimeGetter: Method,
    val userModuleIconGetter: Method,
    val userModuleNameGetter: Method,
    val followDataIdGetter: Method,
    val followDataSourceIdGetter: Method,
    val interactionLikeCountGetter: Method,
    val interactionCommentCountGetter: Method,
    val interactionLikeStatusGetter: Method,
    val microBlogBeanClass: Class<*>,
    val authorTalkListGetter: Method,
    val authorTalkListBeanClass: Class<*>,
    val setTalkId: Method,
    val setSourceId: Method,
    val setCreateTime: Method,
    val setContent: Method,
    val setNickName: Method,
    val setHeadImage: Method,
    val setImageList: Method,
    val setLikeCount: Method,
    val setCommentCount: Method,
    val setIsLike: Method,
    val setAuthorTalkList: Method,
    val microBlogCountSetter: Method,
    val audioGetter: Method,
    val audioBeanClass: Class<*>,
    val setAudioInfoBean: Method?,
    val audioListGetter: Method,
    val audioCountGetter: Method,
    val capsuleGetter: Method,
    val capsuleBeanClass: Class<*>,
    val capsuleBeanConstructor: Constructor<*>,
    val capsuleListSetter: Method,
    val capsuleCountSetter: Method,
    val capsuleListItemClass: Class<*>,
    val capsuleItemConstructor: Constructor<*>,
    val setCapsuleBean: Method?,
    val capsuleEntityClass: Class<*>,
    val capsuleEntityListField: Field,
    val capsuleItemClass: Class<*>,
    val capsuleItemTitleGetter: Method,
    val capsuleItemContextGetter: Method,
    val capsuleItemRichContextGetter: Method,
    val capsuleItemDetailH5UrlGetter: Method,
    val capsuleItemIdGetter: Method,
    val capsuleItemSourceIdGetter: Method,
    val capsuleItemUserModuleGetter: Method,
    val capsuleItemInteractionGetter: Method,
    val capsuleItemTitleSetter: Method,
    val capsuleItemContentSetter: Method,
    val capsuleItemRichContextSetter: Method,
    val capsuleItemDetailH5UrlSetter: Method,
    val capsuleItemIdSetter: Method,
    val capsuleItemSourceIdSetter: Method,
    val capsuleItemCreateTimeSetter: Method,
    val capsuleItemCommentCountSetter: Method,
    val capsuleItemLikeCountSetter: Method,
    val capsuleItemLikeStatusSetter: Method,
    val anchorInfoGetter: Method,
    val anchorInfoBeanClass: Class<*>,
    val anchorInfoBeanConstructor: Constructor<*>,
    val anchorInfoListGetter: Method,
    val anchorInfoListSetter: Method,
    val anchorInfoTotalGetter: Method,
    val anchorInfoTotalSetter: Method,
    val anchorInfoSharedGetter: Method,
    val anchorInfoSharedSetter: Method,
    val cvPayloadClass: Class<*>,
    val cvPayloadListGetter: Method,
    val cvPayloadTotalGetter: Method,
    val cvPayloadSharedGetter: Method,
    val setAnchorInfoBean: Method?,
    val anchorIdGetter: Method,
    val setAnchorId: Method?,
    val personalUserInfoOnClick: Method,
    val actionUrlProcessMethod: Method,
) {
    fun itemType(item: Any): Int? =
        (FeatureHooks.invoke(itemTypeGetter, item) as? Number)?.toInt()

    fun isPrivacyStatEnabled(item: Any): Boolean =
        FeatureHooks.invoke(privacyGetter, item) == true

    fun userId(item: Any): Long? =
        (FeatureHooks.invoke(userIdGetter, item) as? Number)?.toLong()

    companion object {
        fun resolve(classLoader: ClassLoader, processName: String): RestoreMetadata? = runCatching {
            val convertMethod = resolveTargetMethod(
                classLoader = classLoader,
                processName = processName,
                key = ContentToolsTargetKeys.profileHomeResponseConvert,
            )
            val chapterReviewsApi = resolveTargetMethod(
                classLoader = classLoader,
                processName = processName,
                key = ContentToolsTargetKeys.profileChapterReviewsApi,
            )
            val bookReviewsApi = resolveTargetMethod(
                classLoader = classLoader,
                processName = processName,
                key = ContentToolsTargetKeys.profileBookReviewsApi,
            )
            val columnsApi = resolveTargetMethod(
                classLoader = classLoader,
                processName = processName,
                key = ContentToolsTargetKeys.profileColumnsApi,
            )
            val bookListApi = resolveTargetMethod(
                classLoader = classLoader,
                processName = processName,
                key = ContentToolsTargetKeys.profileBookListApi,
            )
            val capsuleApi = resolveTargetMethod(
                classLoader = classLoader,
                processName = processName,
                key = ContentToolsTargetKeys.profileCapsuleApi,
            )
            val cvApi = resolveTargetMethod(
                classLoader = classLoader,
                processName = processName,
                key = ContentToolsTargetKeys.profileCvApi,
            )
            val preloadCallbackSuccessMethod = resolveTargetMethod(
                classLoader = classLoader,
                processName = processName,
                key = ContentToolsTargetKeys.profilePreloadCallbackSuccess,
            )

            val apiClass = convertMethod.declaringClass
            val activityClass = FeatureHooks.findClassOrNull(QDReaderHost.Classes.HOME_PAGE_INFO_ACTIVITY, classLoader)
                ?: throw ClassNotFoundException(QDReaderHost.Classes.HOME_PAGE_INFO_ACTIVITY)
            val responseClass = convertMethod.returnType
            val homePageDataClass = findGenericClass(convertMethod.genericParameterTypes.first()) {
                it.name == QDReaderHost.Classes.HOME_PAGE_DATA
            } ?: FeatureHooks.findClassOrNull(QDReaderHost.Classes.HOME_PAGE_DATA, classLoader)
                ?: throw ClassNotFoundException(QDReaderHost.Classes.HOME_PAGE_DATA)
            val itemClass = findGenericClass(convertMethod.genericReturnType) {
                it.name == QDReaderHost.Classes.HOME_PAGE_ITEM
            } ?: FeatureHooks.findClassOrNull(QDReaderHost.Classes.HOME_PAGE_ITEM, classLoader)
                ?: throw ClassNotFoundException(QDReaderHost.Classes.HOME_PAGE_ITEM)
            val chapterBeanClass = itemClass.getMethod("getChapterReviewBean").returnType
            val circleBeanClass = itemClass.getMethod("getCircleReviewBean").returnType
            val userInfoClass = homePageDataClass.getMethod("getUserInfo").returnType
            val authorInfoClass = homePageDataClass.getMethod("getAuthorInfo").returnType
            val chapterReviewListGetter = chapterBeanClass.getMethod("getChapterReviewList")
            val circleReviewListGetter = circleBeanClass.getMethod("getCircleReviewList")
            val chapterReviewItemClass = findGenericClass(chapterReviewListGetter.genericReturnType) {
                it.name == "${chapterBeanClass.packageNameCompat()}.ChapterReviewListBean"
            } ?: throw ClassNotFoundException("${chapterBeanClass.packageNameCompat()}.ChapterReviewListBean")
            val circleReviewItemClass = findGenericClass(circleReviewListGetter.genericReturnType) {
                it.name == "${circleBeanClass.packageNameCompat()}.CircleReviewListBean"
            } ?: throw ClassNotFoundException("${circleBeanClass.packageNameCompat()}.CircleReviewListBean")
            val preloadCallbackBaseClass = chapterReviewsApi.parameterTypes.getOrNull(3)
                ?: throw NoSuchMethodException("chapter-preload-callback-param")
            if (bookReviewsApi.parameterTypes.getOrNull(3) != preloadCallbackBaseClass) {
                throw NoSuchMethodException("profile-preload-callback-mismatch")
            }
            val preloadCallbackClass = preloadCallbackSuccessMethod.declaringClass
            if (!preloadCallbackBaseClass.isAssignableFrom(preloadCallbackClass) ||
                preloadCallbackClass.enclosingMethod?.declaringClass != apiClass
            ) {
                throw ClassNotFoundException("profile-preload-callback-owner")
            }
            val gsonClass = FeatureHooks.findClassOrNull("com.google.gson.Gson", classLoader)
                ?: throw ClassNotFoundException("com.google.gson.Gson")
            val gsonConstructor = gsonClass.getConstructor()
            val preloadCallbackConstructor = preloadCallbackClass.declaredConstructors.firstOrNull { constructor ->
                constructor.parameterTypes.all { type ->
                    type == apiClass || type == gsonClass || type.isInterface
                }
            } ?: throw NoSuchMethodException("profile-preload-callback-constructor")
            val inflateDataMethod = FeatureHooks.methodsInHierarchy(activityClass).single { method ->
                method.name == "inflateData" &&
                    method.parameterTypes.size == 2 &&
                    List::class.java.isAssignableFrom(method.parameterTypes[0]) &&
                    method.parameterTypes[1] == Boolean::class.javaPrimitiveType &&
                    method.returnType == Void.TYPE
            }
            val personalUserInfoHolderClass = FeatureHooks.findClassOrNull(
                QDReaderHost.Classes.HOME_PAGE_PERSONAL_USER_INFO_HOLDER,
                classLoader,
            ) ?: throw ClassNotFoundException(QDReaderHost.Classes.HOME_PAGE_PERSONAL_USER_INFO_HOLDER)

            val columnGetter = homePageDataClass.getMethod("getColumn")
            val columnBeanClass = columnGetter.returnType
            val columnListGetter = columnBeanClass.getMethod("getColumnList")
            val columnItemClass = findGenericClass(columnListGetter.genericReturnType) {
                it.name == "${columnBeanClass.packageNameCompat()}.ColumnListBean"
            } ?: throw ClassNotFoundException("${columnBeanClass.packageNameCompat()}.ColumnListBean")
            val myBookListBeanClass = homePageDataClass.getMethod("getMyBookList").returnType
            val bookListGetter = myBookListBeanClass.getMethod("getBookLists")
            val bookListItemClass = findGenericClass(bookListGetter.genericReturnType) {
                it.name == QDReaderHost.Classes.RECOM_BOOK_LIST_SIMPLE_ITEM
            } ?: throw ClassNotFoundException(QDReaderHost.Classes.RECOM_BOOK_LIST_SIMPLE_ITEM)

            val capsuleGetter = homePageDataClass.getMethod("getCapsuleBean")
            val capsuleBeanClass = capsuleGetter.returnType
            val capsuleListGetter = capsuleBeanClass.getMethod("getCapsuleList")
            val capsuleListItemClass = findGenericClass(capsuleListGetter.genericReturnType) {
                it.name == QDReaderHost.Classes.CAPSULE_LIST_BEAN
            } ?: throw ClassNotFoundException(QDReaderHost.Classes.CAPSULE_LIST_BEAN)
            val capsuleItemConstructor = capsuleListItemClass.getConstructor()
            val capsuleEntityClass = findGenericClass(capsuleApi.genericReturnType) {
                it.name == QDReaderHost.Classes.MY_COLLECTION_CAPSULE_ENTITY
            } ?: FeatureHooks.findClassOrNull(QDReaderHost.Classes.MY_COLLECTION_CAPSULE_ENTITY, classLoader)
                ?: throw ClassNotFoundException(QDReaderHost.Classes.MY_COLLECTION_CAPSULE_ENTITY)
            val capsuleEntityListField = capsuleEntityClass.getField("capsuleList")
            val capsuleItemClass = findGenericClass(capsuleEntityListField.genericType) {
                it.name == QDReaderHost.Classes.MY_COLLECTION_CAPSULE_ITEM
            } ?: FeatureHooks.findClassOrNull(QDReaderHost.Classes.MY_COLLECTION_CAPSULE_ITEM, classLoader)
                ?: throw ClassNotFoundException(QDReaderHost.Classes.MY_COLLECTION_CAPSULE_ITEM)

            val anchorInfoGetter = homePageDataClass.getMethod("getAnchorInfoBean")
            val anchorInfoBeanClass = anchorInfoGetter.returnType
            val anchorInfoListGetter = anchorInfoBeanClass.getMethod("getList")
            val cvPayloadClass = findGenericClass(cvApi.genericParameterTypes.last()) {
                it.name == QDReaderHost.Classes.ANCHOR_SECOND_PAGE_BOOK_INFO_BEAN
            } ?: FeatureHooks.findClassOrNull(QDReaderHost.Classes.ANCHOR_SECOND_PAGE_BOOK_INFO_BEAN, classLoader)
                ?: throw ClassNotFoundException(QDReaderHost.Classes.ANCHOR_SECOND_PAGE_BOOK_INFO_BEAN)
            val cvPayloadListGetter = cvPayloadClass.getMethod("getList")
            val cvPayloadTotalGetter = cvPayloadClass.getMethod("getTotal")
            val cvPayloadSharedGetter = cvPayloadClass.getMethod("getShared")

            // 动态/配音两条主动请求链：Retrofit 接口方法按 @GET 路径解析（db.w.b / db.q0.judian），
            // 通过 QDRetrofitClient.INSTANCE.getApi(Class) 在后台线程同步请求数据并构造主页 Bean。
            val userFollowApi = resolveTargetMethod(
                classLoader = classLoader,
                processName = processName,
                key = ContentToolsTargetKeys.profileUserFollowApi,
            )
            val userAudioListApi = resolveTargetMethod(
                classLoader = classLoader,
                processName = processName,
                key = ContentToolsTargetKeys.profileUserAudioListApi,
            )
            val userFollowApiClass = userFollowApi.declaringClass
            val userAudioListApiClass = userAudioListApi.declaringClass
            val audioBlockingFirstMethod = userAudioListApi.returnType.methods.singleOrNull { method ->
                Modifier.isPublic(method.modifiers) &&
                    !Modifier.isStatic(method.modifiers) &&
                    method.name == "blockingFirst" &&
                    method.parameterTypes.isEmpty() &&
                    method.returnType != Void.TYPE
            } ?: throw NoSuchMethodException("profile-audio-observable-blockingFirst")
            val retrofitClientClass = FeatureHooks.findClassOrNull(QDReaderHost.Classes.QD_RETROFIT_CLIENT, classLoader)
                ?: throw ClassNotFoundException(QDReaderHost.Classes.QD_RETROFIT_CLIENT)
            val retrofitInstance = runCatching {
                FeatureHooks.read(retrofitClientClass.getField("INSTANCE"), null)
            }.getOrNull()
            val retrofitGetApiMethod = retrofitClientClass.methods.firstOrNull { method ->
                method.name == "getApi" && method.parameterTypes.size == 1 && method.parameterTypes[0] == Class::class.java
            }

            val userFollowBeanClass = findGenericClass(userFollowApi.genericReturnType) {
                it.name == QDReaderHost.Classes.QD_USER_FOLLOW_BEAN
            } ?: throw ClassNotFoundException(QDReaderHost.Classes.QD_USER_FOLLOW_BEAN)
            val getFeedItems = userFollowBeanClass.getMethod("getFeedItems")
            val feedItemClass = findGenericClass(getFeedItems.genericReturnType) {
                it.name == QDReaderHost.Classes.FOLLOW_FEED_ITEM
            } ?: throw ClassNotFoundException(QDReaderHost.Classes.FOLLOW_FEED_ITEM)
            val feedItemContentModuleGetter = feedItemClass.getMethod("getContentModule")
            val followContentModuleClass = findGenericClass(feedItemContentModuleGetter.genericReturnType) {
                it.name == QDReaderHost.Classes.FOLLOW_CONTENT_MODULE
            } ?: throw ClassNotFoundException(QDReaderHost.Classes.FOLLOW_CONTENT_MODULE)
            val feedItemFollowDataGetter = feedItemClass.getMethod("getFollowData")
            val feedItemUserModuleGetter = feedItemClass.getMethod("getUserModule")
            val feedItemInteractionGetter = feedItemClass.getMethod("getInteractionModule")
            val followUserModuleClass = feedItemUserModuleGetter.returnType
            val followDataClass = feedItemFollowDataGetter.returnType
            val followInteractionModuleClass = feedItemInteractionGetter.returnType

            val microBlogGetter = homePageDataClass.getMethod("getMicroBlogBean")
            val microBlogBeanClass = microBlogGetter.returnType
            val authorTalkListGetter = microBlogBeanClass.getMethod("getAuthorTalkList")
            val authorTalkListBeanClass = findGenericClass(authorTalkListGetter.genericReturnType) {
                it.name == "${microBlogBeanClass.name}\$AuthorTalkListBean"
            } ?: throw ClassNotFoundException("${microBlogBeanClass.name}\$AuthorTalkListBean")

            val audioGetter = homePageDataClass.getMethod("getAudioInfo")
            val audioBeanClass = audioGetter.returnType
            val setAudioInfoBean = itemClass.methods.firstOrNull { it.name == "setAudioInfoBean" && it.parameterTypes.size == 1 }

            val capsuleListSetter = capsuleBeanClass.getMethod("setCapsuleList", List::class.java)
            val capsuleCountSetter = capsuleBeanClass.getMethod("setCount", Int::class.javaPrimitiveType)
            val setCapsuleBean = itemClass.methods.firstOrNull {
                it.name == "setCapsuleBean" && it.parameterTypes.size == 1
            }
            val capsuleItemTitleGetter = capsuleItemClass.getMethod("getTitle")
            val capsuleItemContextGetter = capsuleItemClass.getMethod("getContext")
            val capsuleItemRichContextGetter = capsuleItemClass.getMethod("getRichContext")
            val capsuleItemDetailH5UrlGetter = capsuleItemClass.getMethod("getDetailH5Url")
            val capsuleItemIdGetter = capsuleItemClass.getMethod("getId")
            val capsuleItemSourceIdGetter = capsuleItemClass.getMethod("getSourceId")
            val capsuleItemUserModuleGetter = capsuleItemClass.getMethod("getUserModule")
            val capsuleItemInteractionGetter = capsuleItemClass.getMethod("getInteractionModule")
            val capsuleItemTitleSetter = capsuleListItemClass.getMethod("setTitle", String::class.java)
            val capsuleItemContentSetter = capsuleListItemClass.getMethod("setContent", String::class.java)
            val capsuleItemRichContextSetter = capsuleListItemClass.getMethod("setRichContext", String::class.java)
            val capsuleItemDetailH5UrlSetter = capsuleListItemClass.getMethod("setDetailH5Url", String::class.java)
            val capsuleItemIdSetter = capsuleListItemClass.getMethod("setId", Long::class.javaPrimitiveType)
            val capsuleItemSourceIdSetter = capsuleListItemClass.getMethod("setSourceId", Long::class.javaPrimitiveType)
            val capsuleItemCreateTimeSetter = capsuleListItemClass.getMethod("setCreateTime", Long::class.javaPrimitiveType)
            val capsuleItemCommentCountSetter = capsuleListItemClass.getMethod("setCommentCount", Long::class.javaPrimitiveType)
            val capsuleItemLikeCountSetter = capsuleListItemClass.getMethod("setLikeCount", Long::class.javaPrimitiveType)
            val capsuleItemLikeStatusSetter = capsuleListItemClass.getMethod("setLikeStatus", Int::class.javaPrimitiveType)

            val anchorInfoBeanConstructor = anchorInfoBeanClass.getConstructor()
            val anchorInfoListSetter = anchorInfoBeanClass.getMethod("setList", List::class.java)
            val anchorInfoTotalGetter = anchorInfoBeanClass.getMethod("getTotal")
            val anchorInfoTotalSetter = anchorInfoBeanClass.getMethod("setTotal", Int::class.javaPrimitiveType)
            val anchorInfoSharedGetter = anchorInfoBeanClass.getMethod("getShared")
            val anchorInfoSharedSetter = anchorInfoBeanClass.getMethod("setShared", Int::class.javaPrimitiveType)
            val setAnchorInfoBean = itemClass.methods.firstOrNull {
                it.name == "setmAnchorInfoBean" && it.parameterTypes.size == 1
            }
            val anchorIdGetter = itemClass.getMethod("getAnchorId")
            val setAnchorId = itemClass.methods.firstOrNull {
                it.name == "setAnchorId" && it.parameterTypes.contentEquals(arrayOf(Long::class.javaPrimitiveType))
            }

            RestoreMetadata(
                apiClass = apiClass,
                convertMethod = convertMethod,
                chapterReviewsApi = chapterReviewsApi,
                bookReviewsApi = bookReviewsApi,
                capsuleApi = capsuleApi,
                cvApi = cvApi,
                preloadCallbackConstructor = preloadCallbackConstructor,
                preloadCallbackSuccessMethod = preloadCallbackSuccessMethod,
                preloadCallbackErrorMethod = callbackErrorMethod(preloadCallbackClass),
                gsonConstructor = gsonConstructor,
                gsonFromJsonClassMethod = gsonClass.methods.firstOrNull { method ->
                    method.parameterTypes.contentEquals(arrayOf(String::class.java, Class::class.java))
                } ?: throw NoSuchMethodException("gson-from-json-class"),
                inflateDataMethod = inflateDataMethod,
                responseDataField = responseClass.getField("data"),
                homePageDataClass = homePageDataClass,
                itemClass = itemClass,
                chapterReviewItemClass = chapterReviewItemClass,
                circleReviewItemClass = circleReviewItemClass,
                itemConstructor = itemClass.getConstructor(Int::class.javaPrimitiveType),
                chapterBeanConstructor = chapterBeanClass.getConstructor(),
                circleBeanConstructor = circleBeanClass.getConstructor(),
                itemTypeGetter = itemClass.getMethod("getItemType"),
                privacyGetter = itemClass.getMethod("isPrivacyStatEnable"),
                userIdGetter = itemClass.getMethod("getUserId"),
                getChapterBean = itemClass.getMethod("getChapterReviewBean"),
                getCircleBean = itemClass.getMethod("getCircleReviewBean"),
                chapterReviewGetter = homePageDataClass.getMethod("getChapterReview"),
                circleReviewGetter = homePageDataClass.getMethod("getCircleReview"),
                chapterReviewListGetter = chapterReviewListGetter,
                circleReviewListGetter = circleReviewListGetter,
                chapterReviewListSetter = chapterBeanClass.getMethod("setChapterReviewList", List::class.java),
                circleReviewListSetter = circleBeanClass.getMethod("setCircleReviewList", List::class.java),
                chapterReviewCountGetter = chapterBeanClass.getMethod("getCount"),
                circleReviewCountGetter = circleBeanClass.getMethod("getCount"),
                chapterReviewCountSetter = chapterBeanClass.getMethod("setCount", Int::class.javaPrimitiveType),
                circleReviewCountSetter = circleBeanClass.getMethod("setCount", Int::class.javaPrimitiveType),
                userInfoGetter = homePageDataClass.getMethod("getUserInfo"),
                userInfoUserIdGetter = userInfoClass.getMethod("getUserId"),
                authorInfoGetter = homePageDataClass.getMethod("getAuthorInfo"),
                authorInfoIdGetter = authorInfoClass.getMethod("getAuthorId"),
                setUserId = itemClass.getMethod("setUserId", Long::class.javaPrimitiveType),
                setMaster = itemClass.getMethod("setMaster", Boolean::class.javaPrimitiveType),
                setAuthor = itemClass.getMethod("setAuthor", Boolean::class.javaPrimitiveType),
                setPrivacy = itemClass.getMethod("setPrivacyStatEnable", Boolean::class.javaPrimitiveType),
                setChapterBean = itemClass.getMethod("setChapterReviewBean", chapterBeanClass),
                setCircleBean = itemClass.getMethod("setCircleReviewBean", circleBeanClass),
                userInfoBeanGetter = itemClass.getMethod("getUserInfoBean"),
                userInfoBeanSetter = itemClass.getMethod("setUserInfoBean", userInfoClass),
                userInfoClass = userInfoClass,
                authorLightUrlGetter = userInfoClass.getMethod("getAuthorLightUrl"),
                allSubUrlGetter = userInfoClass.getMethod("getAllSubUrl"),
                myBookListGetter = homePageDataClass.getMethod("getMyBookList"),
                yueLiGetter = homePageDataClass.getMethod("getYueLi"),
                yueLiConstructor = itemClass.getMethod("getYueLiBean").returnType.getConstructor(),
                setYueLiUrl = itemClass.getMethod("getYueLiBean").returnType.getMethod("setUrl", String::class.java),
                setYueLiTips = itemClass.getMethod("getYueLiBean").returnType.getMethod("setTips", String::class.java),
                microBlogGetter = homePageDataClass.getMethod("getMicroBlogBean"),
                setMyBookListBean = itemClass.methods.firstOrNull { it.name == "setMyBookListBean" && it.parameterTypes.size == 1 },
                setYueLiBean = itemClass.methods.firstOrNull { it.name == "setYueLiBean" && it.parameterTypes.size == 1 },
                setYueLiStatus = itemClass.methods.firstOrNull {
                    it.name == "setYueLiStatus" && it.parameterTypes.firstOrNull() == Int::class.javaPrimitiveType
                },
                setMicroBlogBean = itemClass.methods.firstOrNull { it.name == "setMicroBlogBean" && it.parameterTypes.size == 1 },
                columnsApi = columnsApi,
                bookListApi = bookListApi,
                columnGetter = columnGetter,
                setColumnBean = itemClass.methods.firstOrNull { it.name == "setColumnBean" && it.parameterTypes.size == 1 },
                columnBeanConstructor = columnBeanClass.getConstructor(),
                columnListGetter = columnListGetter,
                columnListSetter = columnBeanClass.getMethod("setColumnList", List::class.java),
                columnCountSetter = columnBeanClass.getMethod("setCount", Int::class.javaPrimitiveType),
                columnItemClass = columnItemClass,
                bookListBeanConstructor = myBookListBeanClass.getConstructor(),
                bookListSetter = myBookListBeanClass.getMethod("setBookLists", List::class.java),
                bookListCountSetter = myBookListBeanClass.getMethod("setCount", Int::class.javaPrimitiveType),
                bookListItemClass = bookListItemClass,
                capsuleGetter = capsuleGetter,
                capsuleBeanClass = capsuleBeanClass,
                capsuleBeanConstructor = capsuleBeanClass.getConstructor(),
                capsuleListSetter = capsuleListSetter,
                capsuleCountSetter = capsuleCountSetter,
                capsuleListItemClass = capsuleListItemClass,
                capsuleItemConstructor = capsuleItemConstructor,
                setCapsuleBean = setCapsuleBean,
                capsuleEntityClass = capsuleEntityClass,
                capsuleEntityListField = capsuleEntityListField,
                capsuleItemClass = capsuleItemClass,
                capsuleItemTitleGetter = capsuleItemTitleGetter,
                capsuleItemContextGetter = capsuleItemContextGetter,
                capsuleItemRichContextGetter = capsuleItemRichContextGetter,
                capsuleItemDetailH5UrlGetter = capsuleItemDetailH5UrlGetter,
                capsuleItemIdGetter = capsuleItemIdGetter,
                capsuleItemSourceIdGetter = capsuleItemSourceIdGetter,
                capsuleItemUserModuleGetter = capsuleItemUserModuleGetter,
                capsuleItemInteractionGetter = capsuleItemInteractionGetter,
                capsuleItemTitleSetter = capsuleItemTitleSetter,
                capsuleItemContentSetter = capsuleItemContentSetter,
                capsuleItemRichContextSetter = capsuleItemRichContextSetter,
                capsuleItemDetailH5UrlSetter = capsuleItemDetailH5UrlSetter,
                capsuleItemIdSetter = capsuleItemIdSetter,
                capsuleItemSourceIdSetter = capsuleItemSourceIdSetter,
                capsuleItemCreateTimeSetter = capsuleItemCreateTimeSetter,
                capsuleItemCommentCountSetter = capsuleItemCommentCountSetter,
                capsuleItemLikeCountSetter = capsuleItemLikeCountSetter,
                capsuleItemLikeStatusSetter = capsuleItemLikeStatusSetter,
                anchorInfoGetter = anchorInfoGetter,
                anchorInfoBeanClass = anchorInfoBeanClass,
                anchorInfoBeanConstructor = anchorInfoBeanConstructor,
                anchorInfoListGetter = anchorInfoListGetter,
                anchorInfoListSetter = anchorInfoListSetter,
                anchorInfoTotalGetter = anchorInfoTotalGetter,
                anchorInfoTotalSetter = anchorInfoTotalSetter,
                anchorInfoSharedGetter = anchorInfoSharedGetter,
                anchorInfoSharedSetter = anchorInfoSharedSetter,
                cvPayloadClass = cvPayloadClass,
                cvPayloadListGetter = cvPayloadListGetter,
                cvPayloadTotalGetter = cvPayloadTotalGetter,
                cvPayloadSharedGetter = cvPayloadSharedGetter,
                setAnchorInfoBean = setAnchorInfoBean,
                anchorIdGetter = anchorIdGetter,
                setAnchorId = setAnchorId,
                userFollowApi = userFollowApi,
                userFollowApiClass = userFollowApiClass,
                userAudioListApi = userAudioListApi,
                userAudioListApiClass = userAudioListApiClass,
                audioBlockingFirstMethod = audioBlockingFirstMethod,
                retrofitInstance = retrofitInstance,
                retrofitGetApiMethod = retrofitGetApiMethod,
                getFeedItems = getFeedItems,
                feedItemContentModuleGetter = feedItemContentModuleGetter,
                feedItemFollowDataGetter = feedItemFollowDataGetter,
                feedItemUserModuleGetter = feedItemUserModuleGetter,
                feedItemInteractionGetter = feedItemInteractionGetter,
                contentContextGetter = followContentModuleClass.getMethod("getContext"),
                contentImageListGetter = followContentModuleClass.getMethod("getImageList"),
                userModuleTimeGetter = followUserModuleClass.getMethod("getTime"),
                userModuleIconGetter = followUserModuleClass.getMethod("getUserIcon"),
                userModuleNameGetter = followUserModuleClass.getMethod("getUserName"),
                followDataIdGetter = followDataClass.getMethod("getId"),
                followDataSourceIdGetter = followDataClass.getMethod("getSourceId"),
                interactionLikeCountGetter = followInteractionModuleClass.getMethod("getLikeCount"),
                interactionCommentCountGetter = followInteractionModuleClass.getMethod("getCommentCount"),
                interactionLikeStatusGetter = followInteractionModuleClass.getMethod("getLikeStatus"),
                microBlogBeanClass = microBlogBeanClass,
                authorTalkListGetter = authorTalkListGetter,
                authorTalkListBeanClass = authorTalkListBeanClass,
                setTalkId = authorTalkListBeanClass.getMethod("setTalkId", Long::class.javaPrimitiveType),
                setSourceId = authorTalkListBeanClass.getMethod("setSourceId", Long::class.javaPrimitiveType),
                setCreateTime = authorTalkListBeanClass.getMethod("setCreateTime", Long::class.javaPrimitiveType),
                setContent = authorTalkListBeanClass.getMethod("setContent", String::class.java),
                setNickName = authorTalkListBeanClass.getMethod("setNickName", String::class.java),
                setHeadImage = authorTalkListBeanClass.getMethod("setHeadImage", String::class.java),
                setImageList = authorTalkListBeanClass.getMethod("setImageList", List::class.java),
                setLikeCount = authorTalkListBeanClass.getMethod("setLikeCount", Int::class.javaPrimitiveType),
                setCommentCount = authorTalkListBeanClass.getMethod("setCommentCount", Int::class.javaPrimitiveType),
                setIsLike = authorTalkListBeanClass.getMethod("setIsLike", Int::class.javaPrimitiveType),
                setAuthorTalkList = microBlogBeanClass.getMethod("setAuthorTalkList", List::class.java),
                microBlogCountSetter = microBlogBeanClass.getMethod("setCount", Int::class.javaPrimitiveType),
                audioGetter = audioGetter,
                audioBeanClass = audioBeanClass,
                setAudioInfoBean = setAudioInfoBean,
                audioListGetter = audioBeanClass.getMethod("getDataList"),
                audioCountGetter = audioBeanClass.getMethod("getTotalCount"),
                personalUserInfoOnClick = resolvePersonalUserInfoOnClick(personalUserInfoHolderClass),
                actionUrlProcessMethod = resolveActionUrlProcess(classLoader),
            )
        }.onFailure { throwable ->
            FeatureHooks.w(
                "Profile privacy restore metadata unavailable: ${throwable.javaClass.name}: " +
                    (throwable.message ?: "<no-message>"),
            )
        }.getOrNull()

        private fun resolveTargetMethod(
            classLoader: ClassLoader,
            processName: String,
            key: TargetKey.MethodTarget,
        ): Method = HostTargetResolver.resolveMethod(
            context = HostSessionStore.contextOrNull(),
            classLoader = classLoader,
            processName = processName,
            key = key,
        ) ?: throw NoSuchMethodException(key.id.value)

        private fun callbackErrorMethod(clazz: Class<*>): Method? =
            FeatureHooks.methodsInHierarchy(clazz).firstOrNull { method ->
                !Modifier.isAbstract(method.modifiers) &&
                    method.returnType == Void.TYPE &&
                    method.parameterTypes.size == 2 &&
                    method.parameterTypes[0] != String::class.java &&
                    method.parameterTypes[0] != Throwable::class.java &&
                    method.parameterTypes[1] == String::class.java
            }

        private fun resolvePersonalUserInfoOnClick(clazz: Class<*>): Method =
            FeatureHooks.methodsInHierarchy(clazz).firstOrNull { method ->
                method.name == "onClick" &&
                    method.parameterTypes.contentEquals(arrayOf(android.view.View::class.java)) &&
                    method.returnType == Void.TYPE
            } ?: throw NoSuchMethodException("personal-user-info-onClick")

        private fun resolveActionUrlProcess(classLoader: ClassLoader): Method {
            val clazz = FeatureHooks.findClassOrNull(QDReaderHost.Classes.ACTION_URL_PROCESS, classLoader)
                ?: throw ClassNotFoundException(QDReaderHost.Classes.ACTION_URL_PROCESS)
            return FeatureHooks.methodsInHierarchy(clazz).firstOrNull { method ->
                Modifier.isStatic(method.modifiers) && method.name == "process" &&
                    method.parameterTypes.size == 2 &&
                    method.parameterTypes[0].isAssignableFrom(Context::class.java) &&
                    method.parameterTypes[1] == Uri::class.java
            } ?: throw NoSuchMethodException("action-url-process-context-uri")
        }

        private fun findGenericClass(
            type: Type,
            predicate: (Class<*>) -> Boolean,
        ): Class<*>? = genericClasses(type).firstOrNull(predicate)

        private fun Class<*>.packageNameCompat(): String = name.substringBeforeLast('.', "")

        private fun genericClasses(type: Type): Sequence<Class<*>> = sequence {
            when (type) {
                is Class<*> -> yield(type)
                is ParameterizedType -> {
                    val rawType = type.rawType
                    if (rawType is Class<*>) yield(rawType)
                    type.actualTypeArguments.forEach { argument ->
                        yieldAll(genericClasses(argument))
                    }
                }
                is WildcardType -> {
                    type.upperBounds.forEach { bound -> yieldAll(genericClasses(bound)) }
                    type.lowerBounds.forEach { bound -> yieldAll(genericClasses(bound)) }
                }
                is TypeVariable<*> -> {
                    type.bounds.forEach { bound -> yieldAll(genericClasses(bound)) }
                }
                is GenericArrayType -> yieldAll(genericClasses(type.genericComponentType))
            }
        }
    }
}
