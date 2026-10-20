package com.xiyunmn.qdhelper.host.resolver.dexkit.query

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import com.xiyunmn.qdhelper.host.resolver.reflection.ReflectionAccess
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Type
import java.nio.ByteBuffer
import java.util.List
import org.json.JSONObject

internal object HostMethodContracts {
    fun isApplicationTypefaceGetter(method: Method): Boolean {
        if (!Modifier.isPublic(method.modifiers) || Modifier.isStatic(method.modifiers) ||
            method.parameterTypes.isNotEmpty() || method.returnType != Typeface::class.java
        ) {
            return false
        }
        return method.declaringClass.interfaces.any { contract ->
            ReflectionAccess.methodsDeclaredBy(contract).count { candidate ->
                candidate.parameterTypes.isEmpty() && candidate.returnType == Typeface::class.java
            } == 2
        }
    }

    fun isFlutterNativeFontRegistrar(
        method: Method,
        engineName: String = HostSymbols.Classes.FLUTTER_ENGINE,
    ): Boolean {
        if (!Modifier.isPublic(method.modifiers) || !Modifier.isStatic(method.modifiers) ||
            method.returnType != Void.TYPE || method.parameterTypes.size != 2 ||
            method.parameterTypes[0].name != engineName
        ) {
            return false
        }
        val api = method.parameterTypes[1]
        if (!api.isInterface) return false
        val apiMethods = ReflectionAccess.methodsDeclaredBy(api)
        return apiMethods.size == 1 && apiMethods.single().let { candidate ->
            candidate.parameterTypes.contentEquals(arrayOf(String::class.java)) &&
                candidate.returnType == ByteBuffer::class.java
        }
    }

    fun isNewBookInvestDetailApi(method: Method): Boolean {
        val params = method.parameterTypes
        if (!Modifier.isPublic(method.modifiers) || !Modifier.isStatic(method.modifiers) ||
            method.returnType != Void.TYPE || params.size != 3 ||
            !Context::class.java.isAssignableFrom(params[0]) ||
            params[1] != Long::class.javaPrimitiveType || params[2].isInterface
        ) {
            return false
        }
        val callbackMethods = ReflectionAccess.methodsDeclaredBy(params[2])
        val responseMethods = callbackMethods.filter { candidate ->
            Modifier.isPublic(candidate.modifiers) &&
                !Modifier.isStatic(candidate.modifiers) &&
                candidate.returnType == Void.TYPE &&
                candidate.parameterTypes.size == 1
        }
        return responseMethods.count { it.name == "onSuccess" } == 1 &&
            responseMethods.count { it.name == "onError" } == 1 &&
            responseMethods.map { it.parameterTypes[0] }.distinct().size == 1
    }

    fun isLegacyRxHttpAdapter(
        method: Method,
        observableName: String = HostSymbols.Classes.RX_OBSERVABLE,
    ): Boolean {
        val params = method.parameterTypes
        if (!Modifier.isPublic(method.modifiers) || !Modifier.isStatic(method.modifiers) ||
            params.size != 3 || !params[0].isInterface ||
            params[1] != Type::class.java || params[2] != Boolean::class.javaPrimitiveType ||
            method.returnType.name != observableName
        ) {
            return false
        }
        val bridgeMethods = ReflectionAccess.methodsDeclaredBy(params[0]).filter { candidate ->
            Modifier.isAbstract(candidate.modifiers) && candidate.returnType == Void.TYPE
        }
        return bridgeMethods.size == 1 &&
            bridgeMethods.single().parameterTypes.size == 1 &&
            !bridgeMethods.single().parameterTypes[0].isInterface
    }

    fun isNewBookInvestRequestApi(
        method: Method,
        observableName: String = HostSymbols.Classes.RX_OBSERVABLE,
    ): Boolean {
        val params = method.parameterTypes
        return Modifier.isPublic(method.modifiers) && Modifier.isAbstract(method.modifiers) &&
            params.size == 8 && params[0] == Long::class.javaPrimitiveType &&
            params[1] == String::class.java && params[2] == Int::class.javaPrimitiveType &&
            params.drop(3).all { it == String::class.java } &&
            method.returnType.name == observableName
    }

    fun isReaderBookIdGetter(
        method: Method,
        ownerName: String = HostSymbols.Classes.QD_READER_ACTIVITY,
    ): Boolean =
        method.declaringClass.name == ownerName &&
            Modifier.isPublic(method.modifiers) && !Modifier.isStatic(method.modifiers) &&
            method.parameterTypes.isEmpty() && method.returnType == Long::class.javaPrimitiveType

    fun isQdInfoCandidate(method: Method, ownerName: String = HostSymbols.Classes.COMMON_LIB_DEVICE): Boolean =
        method.declaringClass.name == ownerName &&
            Modifier.isPublic(method.modifiers) &&
            !Modifier.isStatic(method.modifiers) &&
            (method.parameterTypes.contentEquals(arrayOf(Long::class.javaPrimitiveType, String::class.java)) ||
                method.parameterTypes.contentEquals(arrayOf(Long::class.javaPrimitiveType, String::class.java, Boolean::class.javaPrimitiveType))) &&
            method.returnType == String::class.java

    fun isCommonDeviceStringSetter(method: Method): Boolean =
        method.declaringClass.name == HostSymbols.Classes.COMMON_LIB_DEVICE &&
            Modifier.isPublic(method.modifiers) &&
            Modifier.isStatic(method.modifiers) &&
            method.parameterTypes.contentEquals(arrayOf(String::class.java)) &&
            method.returnType == Void.TYPE

    /**
     * The AppConfig cache/network stage callbacks are declared on an anonymous subclass of the
     * config loader. Both the loader and anonymous class names are
     * compiler-synthesized and reorder freely between builds, so the contract anchors on the
     * *inheritance* relation plus the concrete parameter type instead of the synthetic FQCN.
     *
     * Requiring the `AppConfigBean` parameter also excludes the generic bridge method that the
     * compiler emits alongside each override.
     */
    fun isAppConfigStage(
        method: Method,
        appConfigBeanName: String = HostSymbols.Classes.APP_CONFIG_BEAN,
    ): Boolean =
        !Modifier.isStatic(method.modifiers) &&
            !method.isBridge &&
            !method.isSynthetic &&
            method.parameterTypes.size == 1 &&
            method.parameterTypes[0].name == appConfigBeanName &&
            method.returnType == Void.TYPE &&
            generateSequence(method.declaringClass.superclass) { it.superclass }
                .any(HostInfrastructureContracts::isConfigLoader)

    /**
     * The reading-time DAO insert: `public boolean(ReadTimeEntity)`.
     *
     * Anchored purely on shape plus the SDK parameter type — the declaring class is obfuscated and
     * its package drifts every release, so it must not appear here. The parameter type is the only
     * name in the contract and it comes from the un-obfuscated statistics SDK.
     */
    fun isReadTimeInsert(
        method: Method,
        readTimeEntityName: String = HostSymbols.Classes.READ_TIME_ENTITY,
    ): Boolean =
        Modifier.isPublic(method.modifiers) &&
            !Modifier.isStatic(method.modifiers) &&
            !Modifier.isAbstract(method.modifiers) &&
            !method.isSynthetic &&
            !method.isBridge &&
            method.parameterTypes.size == 1 &&
            method.parameterTypes[0].name == readTimeEntityName &&
            method.returnType == Boolean::class.javaPrimitiveType

    fun isReadTimeMarkReported(
        method: Method,
    ): Boolean =
        Modifier.isPublic(method.modifiers) &&
            !Modifier.isStatic(method.modifiers) &&
            !Modifier.isAbstract(method.modifiers) &&
            !method.isSynthetic &&
            !method.isBridge &&
            method.parameterTypes.contentEquals(arrayOf(List::class.java)) &&
            method.returnType == Boolean::class.javaPrimitiveType

    fun isNotificationDialogRequest(
        method: Method,
        ownerName: String = HostSymbols.Classes.NOTIFICATION_PERMISSION_UTIL,
        dialogManagerName: String = HostSymbols.Classes.QD_DIALOG_MANAGER,
        rxActivityName: String = HostSymbols.Classes.RX_APP_COMPAT_ACTIVITY,
        commonDialogCallbackName: String = HostSymbols.Classes.COMMON_LIB_DIALOG_CALLBACK,
    ): Boolean {
        val params = method.parameterTypes
        return method.declaringClass.name == ownerName &&
            Modifier.isPublic(method.modifiers) &&
            Modifier.isStatic(method.modifiers) &&
            method.returnType == Void.TYPE &&
            params.size == 6 &&
            params[0].name == dialogManagerName &&
            (params[1].name == rxActivityName || params[1] == Activity::class.java) &&
            params[2].name == commonDialogCallbackName &&
            params[3] == String::class.java &&
            params[4] == Int::class.javaPrimitiveType &&
            isNotificationPermissionCallback(params[5], ownerName)
    }

    fun isNotificationSceneGuide(
        method: Method,
        ownerName: String = HostSymbols.Classes.NOTIFICATION_PERMISSION_UTIL,
        rxActivityName: String = HostSymbols.Classes.RX_APP_COMPAT_ACTIVITY,
    ): Boolean {
        val params = method.parameterTypes
        return method.declaringClass.name == ownerName &&
            Modifier.isPublic(method.modifiers) &&
            Modifier.isStatic(method.modifiers) &&
            method.returnType == Boolean::class.javaPrimitiveType &&
            params.size == 4 &&
            params[0] == Int::class.javaPrimitiveType &&
            params[1].name == rxActivityName &&
            params[2] == Long::class.javaPrimitiveType &&
            params[3] == String::class.java
    }

    fun isBookshelfNewListBookBind(
        method: Method,
        ownerName: String = HostSymbols.Classes.BOOKSHELF_NEW_LIST_BOOK_VIEW_HOLDER,
        bindingName: String = HostSymbols.Classes.ITEM_BOOKSHELF_LIST_BINDING,
        shelfItemName: String = HostSymbols.Classes.BOOK_SHELF_ITEM,
        coroutineContextName: String = "kotlin.coroutines.CoroutineContext",
    ): Boolean {
        val params = method.parameterTypes
        return method.declaringClass.name == ownerName &&
            Modifier.isPublic(method.modifiers) &&
            Modifier.isFinal(method.modifiers) &&
            !Modifier.isStatic(method.modifiers) &&
            method.returnType == Void.TYPE &&
            params.size == 5 &&
            params[0].name == bindingName &&
            params[1] == Int::class.javaPrimitiveType &&
            params[2].name == shelfItemName &&
            isCoroutineScope(params[3], coroutineContextName) &&
            params[4] == Int::class.javaPrimitiveType
    }

    /**
     * SanJiangAdapter has two erased `(List) -> void` setters: one for the book rows and one for
     * the header entrances. The public model generic is retained throughout the active audit interval
     * hosts, so use `List<SanJiangStoreItem>` to distinguish the data setter without depending on
     * its obfuscated short name (`p` in those builds).
     */
    fun isSanJiangBookListSetter(
        method: Method,
        ownerName: String = HostSymbols.Classes.SAN_JIANG_ADAPTER,
        itemName: String = HostSymbols.Classes.SAN_JIANG_STORE_ITEM,
    ): Boolean =
        method.declaringClass.name == ownerName &&
            Modifier.isPublic(method.modifiers) &&
            !Modifier.isStatic(method.modifiers) &&
            !method.isBridge &&
            !method.isSynthetic &&
            method.returnType == Void.TYPE &&
            method.parameterTypes.contentEquals(arrayOf(List::class.java)) &&
            method.genericParameterTypes.singleOrNull()?.compatibleName()?.contains(itemName) == true

    fun isRankingRightDataSetter(
        method: Method,
        ownerName: String = "com.qidian.QDReader.ui.adapter.RankingRightAdapter",
        rankingItemName: String = "com.qidian.QDReader.repository.entity.RankingListItem",
    ): Boolean {
        val params = method.parameterTypes
        if (method.declaringClass.name != ownerName || !Modifier.isPublic(method.modifiers) ||
            Modifier.isStatic(method.modifiers) || method.returnType != Void.TYPE ||
            params.size !in setOf(5, 7) || !List::class.java.isAssignableFrom(params[0])
        ) return false
        val tail = params.drop(1).map { it.name }
        return tail == listOf("java.lang.String", "int", "long", rankingItemName) ||
            tail == listOf("java.lang.String", "java.lang.String", "int", "int", "long", rankingItemName)
    }

    // The loading dialog's obfuscated name changes from f to i in 7.9.480.
    // It wraps a dialog rather than extending android.app.Dialog. Verify the shared
    // framework base's Context field and public window operations instead of its short name.
    fun isWelfareDialog(type: Class<*>, expectedName: String? = null): Boolean {
        if (expectedName != null) return type.name == expectedName
        if (!type.name.startsWith("com.qd.ui.component.widget.dialog.")) return false
        return generateSequence(type.superclass) { it.superclass }.any { base ->
            base.name.startsWith("com.qidian.QDReader.framework.widget.dialog.") &&
                ReflectionAccess.fieldsDeclaredBy(base).any {
                    it.name == "mContext" && it.type == Context::class.java && !Modifier.isStatic(it.modifiers)
                } && listOf("show" to Void.TYPE, "dismiss" to Void.TYPE, "isShowing" to Boolean::class.javaPrimitiveType).all { (name, result) ->
                    ReflectionAccess.methodsDeclaredBy(base).any {
                        it.name == name && it.parameterTypes.isEmpty() && it.returnType == result &&
                            Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers)
                    }
                }
        }
    }

    fun isWelfareFinishWatchBusiness(
        method: Method,
        ownerName: String = HostSymbols.Classes.WELFARE_AD_EXTRA,
        ownerPackagePrefix: String = "com.qidian.QDReader.extras.",
        dialogName: String? = null,
        verifierName: String = HostSymbols.Classes.UNIVERSAL_VERIFY_HELPER,
        rewardConfigName: String = "com.qidian.QDReader.repository.entity.config.RewardVideoConfig",
    ): Boolean {
        val params = method.parameterTypes
        val ownerMatches = method.declaringClass.name == ownerName ||
            method.declaringClass.name.startsWith(ownerPackagePrefix)
        if (!ownerMatches || !Modifier.isStatic(method.modifiers) ||
            method.returnType != Void.TYPE || params.size !in setOf(6, 7, 9) ||
            !isWelfareDialog(params[0], dialogName)
        ) {
            return false
        }
        val atomicInt = "java.util.concurrent.atomic.AtomicInteger"
        val ref = "java.util.concurrent.atomic.AtomicReference"
        val oldTail = listOf(atomicInt, "int", ref, verifierName)
        return params.drop(1).map { it.name } in listOf(
            oldTail + "android.util.Pair",
            oldTail + listOf(rewardConfigName, "android.util.Pair"),
            listOf(atomicInt, atomicInt, "int", ref, verifierName, ref, rewardConfigName, "android.util.Pair"),
        )
    }

    private fun isNotificationPermissionCallback(type: Class<*>, ownerName: String): Boolean {
        if (!type.isInterface || type.enclosingClass?.name != ownerName) {
            return false
        }
        val methods = ReflectionAccess.methodsDeclaredBy(type)
        return methods.size == 3 && methods.all { candidate ->
            Modifier.isAbstract(candidate.modifiers) &&
                candidate.returnType == Void.TYPE &&
                candidate.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType))
        }
    }

    private fun isCoroutineScope(type: Class<*>, coroutineContextName: String): Boolean {
        if (!type.isInterface) {
            return false
        }
        return ReflectionAccess.methodsDeclaredBy(type).any { candidate ->
            candidate.parameterTypes.isEmpty() && candidate.returnType.name == coroutineContextName
        }
    }

    fun isProfileHomeResponseConvert(
        method: Method,
        serverResponseName: String = HostSymbols.Classes.SERVER_RESPONSE,
        homePageDataName: String = HostSymbols.Classes.HOME_PAGE_DATA,
        homePageItemName: String = HostSymbols.Classes.HOME_PAGE_ITEM,
    ): Boolean {
        if (!Modifier.isStatic(method.modifiers) ||
            method.parameterTypes.size != 2 ||
            method.parameterTypes[0].name != serverResponseName ||
            method.parameterTypes[1] != Boolean::class.javaPrimitiveType ||
            method.returnType.name != serverResponseName
        ) {
            return false
        }
        val genericSignature = (method.genericParameterTypes.toList() + method.genericReturnType)
            .joinToString("\n") { it.compatibleName() }
        if (genericSignature.contains(homePageDataName) && genericSignature.contains(homePageItemName)) {
            return true
        }
        return ReflectionAccess.methodsDeclaredBy(method.declaringClass).count(::isProfileSecondPageApiShape) >= 2
    }

    fun isProfileSecondPageApi(
        method: Method,
        callbackBaseName: String? = null,
    ): Boolean =
        isProfileSecondPageApiShape(method) &&
            callbackBaseName?.let { method.parameterTypes[3].name == it } != false

    /**
     * The author-book-list second page API (`RecomBookListMoreDataActivity`), shaped
     * `(Context, long userId, int pageIndex, long filterBookListId, boolean loadMore, callback)`
     * with a non-interface callback (the host's `t6.judian` abstract class). Distinct from
     * [isProfileSecondPageApiShape] which requires exactly four parameters.
     */
    fun isProfileBookListApi(method: Method): Boolean =
        Modifier.isPublic(method.modifiers) &&
            Modifier.isStatic(method.modifiers) &&
            method.returnType == Void.TYPE &&
            method.parameterTypes.size == 6 &&
            Context::class.java.isAssignableFrom(method.parameterTypes[0]) &&
            method.parameterTypes[1] == Long::class.javaPrimitiveType &&
            method.parameterTypes[2] == Int::class.javaPrimitiveType &&
            method.parameterTypes[3] == Long::class.javaPrimitiveType &&
            method.parameterTypes[4] == Boolean::class.javaPrimitiveType &&
            !method.parameterTypes[5].isInterface

    fun isProfilePreloadCallbackSuccess(
        method: Method,
        serverResponseName: String = HostSymbols.Classes.SERVER_RESPONSE,
        homePageDataName: String = HostSymbols.Classes.HOME_PAGE_DATA,
        homePageItemName: String = HostSymbols.Classes.HOME_PAGE_ITEM,
    ): Boolean {
        if (!Modifier.isPublic(method.modifiers) || Modifier.isStatic(method.modifiers) ||
            method.returnType != Void.TYPE ||
            !method.parameterTypes.contentEquals(
                arrayOf(JSONObject::class.java, String::class.java, Int::class.javaPrimitiveType),
            )
        ) {
            return false
        }
        val callbackClass = method.declaringClass
        if (Modifier.isAbstract(callbackClass.modifiers)) return false
        val callbackBase = callbackClass.superclass ?: return false
        val overridesAbstractSuccess = callbackBase.methods.any { candidate ->
            Modifier.isAbstract(candidate.modifiers) &&
                candidate.returnType == method.returnType &&
                candidate.parameterTypes.contentEquals(method.parameterTypes)
        }
        if (!overridesAbstractSuccess) return false
        val apiOwner = callbackClass.enclosingMethod?.declaringClass ?: return false
        val ownerMethods = ReflectionAccess.methodsDeclaredBy(apiOwner)
        val secondPageApis = ownerMethods.filter(::isProfileSecondPageApiShape)
        return secondPageApis.count { candidate -> candidate.parameterTypes[3] == callbackBase } >= 2 &&
            ownerMethods.any { candidate ->
                isProfileHomeResponseConvert(
                    candidate,
                    serverResponseName,
                    homePageDataName,
                    homePageItemName,
                )
            }
    }

    /**
     * The profile user-follow (动态/TA的动态) feed API: `db.w.b` =
     * `@GET /argus/api/v1/dynamic/getByUser` returning `Call<ServerResponse<QDUserFollowBean>>`.
     * Anchored on the exact Retrofit @GET path plus the `(long, int, int)` shape and the generic
     * payload, so the obfuscated interface short name never needs to appear in the contract.
     */
    fun isProfileUserFollowApi(
        method: Method,
        path: String = "/argus/api/v1/dynamic/getByUser",
        serverResponseName: String = HostSymbols.Classes.SERVER_RESPONSE,
        userFollowBeanName: String = HostSymbols.Classes.QD_USER_FOLLOW_BEAN,
    ): Boolean {
        val genericReturnType = method.genericReturnType.compatibleName()
        return Modifier.isPublic(method.modifiers) &&
            Modifier.isAbstract(method.modifiers) &&
            !Modifier.isStatic(method.modifiers) &&
            method.hasExactRetrofitPath(path) &&
            method.parameterTypes.contentEquals(
                arrayOf(
                    Long::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                ),
            ) &&
            method.returnType.name == "retrofit2.Call" &&
            genericReturnType.contains(serverResponseName) &&
            genericReturnType.contains(userFollowBeanName)
    }

    /**
     * The profile audio-list API: `db.q0.judian` =
     * `@GET /argus/api/v1/user/getuserpageinfoaudiolist` returning
     * `io.reactivex.r<ServerResponse<AudioInfoBean>>`. Anchored on the exact @GET path, the
     * `(long, int, int)` shape and the generic payload, same drift-proof contract as
     * [isProfileUserFollowApi].
     */
    fun isProfileUserAudioListApi(
        method: Method,
        path: String = "/argus/api/v1/user/getuserpageinfoaudiolist",
        serverResponseName: String = HostSymbols.Classes.SERVER_RESPONSE,
        audioInfoBeanName: String = HostSymbols.Classes.AUDIO_INFO_BEAN,
    ): Boolean {
        val genericReturnType = method.genericReturnType.compatibleName()
        return Modifier.isPublic(method.modifiers) &&
            Modifier.isAbstract(method.modifiers) &&
            !Modifier.isStatic(method.modifiers) &&
            method.hasExactRetrofitPath(path) &&
            method.parameterTypes.contentEquals(
                arrayOf(
                    Long::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                ),
            ) &&
            !method.returnType.isPrimitive &&
            method.returnType != Void.TYPE &&
            genericReturnType.contains(serverResponseName) &&
            genericReturnType.contains(audioInfoBeanName) &&
            method.returnType.methods.any { candidate ->
                Modifier.isPublic(candidate.modifiers) &&
                    !Modifier.isStatic(candidate.modifiers) &&
                    candidate.name == "blockingFirst" &&
                    candidate.parameterTypes.isEmpty() &&
                    candidate.returnType != Void.TYPE
            }
    }

    /**
     * The personal capsule API: `@GET /argus/api/v1/capsulenovel/createByUser`
     * returning `io.reactivex.r<ServerResponse<MyCollectionCapsuleEntity>>`.
     * The declaring interface is intentionally not part of this contract because it moved
     * outside the component.api package in recent host builds.
     */
    fun isProfileCapsuleApi(
        method: Method,
        path: String = "/argus/api/v1/capsulenovel/createByUser",
        serverResponseName: String = HostSymbols.Classes.SERVER_RESPONSE,
        capsuleEntityName: String = HostSymbols.Classes.MY_COLLECTION_CAPSULE_ENTITY,
        observableName: String = HostSymbols.Classes.RX_OBSERVABLE,
    ): Boolean {
        val genericReturnType = method.genericReturnType.compatibleName()
        return Modifier.isPublic(method.modifiers) &&
            Modifier.isAbstract(method.modifiers) &&
            !Modifier.isStatic(method.modifiers) &&
            method.hasExactRetrofitPath(path) &&
            method.parameterTypes.contentEquals(
                arrayOf(
                    Long::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                ),
            ) &&
            method.returnType.name == observableName &&
            genericReturnType.contains(serverResponseName) &&
            genericReturnType.contains(capsuleEntityName) &&
            method.returnType.methods.any { candidate ->
                Modifier.isPublic(candidate.modifiers) &&
                    !Modifier.isStatic(candidate.modifiers) &&
                    candidate.name == "blockingFirst" &&
                    candidate.parameterTypes.isEmpty() &&
                    candidate.returnType != Void.TYPE
            }
    }

    /**
     * The Kotlin suspend CV/配音 works API.  The compiler emits two String query parameters,
     * followed by a Continuation-like interface, and an Object return type.  The payload type
     * lives in the continuation's generic parameter rather than the method return type, so the
     * final generic signature is part of the contract.
     */
    fun isProfileCvApi(
        method: Method,
        path: String = "/argus/api/v1/audio/queryAnchorAudioPageList",
        serverResponseName: String = HostSymbols.Classes.SERVER_RESPONSE,
        payloadName: String = HostSymbols.Classes.ANCHOR_SECOND_PAGE_BOOK_INFO_BEAN,
        continuationPackage: String = "kotlin.coroutines",
    ): Boolean {
        if (!Modifier.isPublic(method.modifiers) ||
            !Modifier.isAbstract(method.modifiers) ||
            Modifier.isStatic(method.modifiers) ||
            !method.hasExactRetrofitPath(path) ||
            method.parameterTypes.size < 3 ||
            method.parameterTypes[0] != String::class.java ||
            method.parameterTypes[1] != String::class.java
        ) {
            return false
        }
        val continuationType = method.parameterTypes.last()
        val genericContinuation = method.genericParameterTypes.lastOrNull()?.compatibleName().orEmpty()
        return continuationType.isInterface &&
            continuationType.name.contains(continuationPackage) &&
            genericContinuation.contains(serverResponseName) &&
            genericContinuation.contains(payloadName) &&
            !method.returnType.isPrimitive &&
            method.returnType != Void.TYPE
    }

    private fun isProfileSecondPageApiShape(method: Method): Boolean =
        Modifier.isPublic(method.modifiers) &&
            Modifier.isStatic(method.modifiers) &&
            method.returnType == Void.TYPE &&
            method.parameterTypes.size == 4 &&
            Context::class.java.isAssignableFrom(method.parameterTypes[0]) &&
            method.parameterTypes[1] == Long::class.javaPrimitiveType &&
            method.parameterTypes[2] == Int::class.javaPrimitiveType &&
            !method.parameterTypes[3].isInterface
}

private fun Type.compatibleName(): String = if (this is Class<*>) name else toString()
