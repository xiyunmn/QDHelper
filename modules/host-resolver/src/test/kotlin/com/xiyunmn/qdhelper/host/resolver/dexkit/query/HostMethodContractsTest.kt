package com.xiyunmn.qdhelper.host.resolver.dexkit.query

import android.app.Dialog
import android.util.Pair
import com.qd.ui.component.widget.dialog.LoadingDialogFixture
import com.qd.ui.component.widget.dialog.NonDialogFixture
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import android.graphics.Typeface
import java.nio.ByteBuffer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.Continuation

class HostMethodContractsTest {
    @Test fun rankingAcceptsBothOrderedSignaturesButRejectsLookalikes() {
        RankingFixture::class.java.declaredMethods.forEach { method ->
            val accepted = HostMethodContracts.isRankingRightDataSetter(
                method, RankingFixture::class.java.name, RankingItem::class.java.name,
            )
            if (method.name in setOf("legacy", "current")) assertTrue(method.name, accepted)
            else assertFalse(method.name, accepted)
        }
    }

    @Test fun qdInfoAcceptsOptionalBooleanWithoutAcceptingOtherOverloads() {
        DeviceFixture::class.java.declaredMethods.forEach { method ->
            val accepted = HostMethodContracts.isQdInfoCandidate(method, DeviceFixture::class.java.name)
            if (method.name in setOf("legacy", "current")) assertTrue(method.name, accepted)
            else assertFalse(method.name, accepted)
        }
    }

    @Test fun renamedWelfareDialogRequiresComponentNamespaceAndWrapperContract() {
        assertTrue(HostMethodContracts.isWelfareDialog(LoadingDialogFixture::class.java))
        assertFalse(HostMethodContracts.isWelfareDialog(NonDialogFixture::class.java))
        assertFalse(HostMethodContracts.isWelfareDialog(Dialog::class.java))
        FinishFixture::class.java.declaredMethods.forEach { method ->
            val accepted = HostMethodContracts.isWelfareFinishWatchBusiness(
                method, ownerName = FinishFixture::class.java.name,
                verifierName = VerifyFixture::class.java.name, rewardConfigName = ConfigFixture::class.java.name,
            )
            if (method.name == "renamedDialog") assertTrue(accepted) else assertFalse(accepted)
        }
    }

    private class RankingItem
    private class RankingFixture {
        fun legacy(items: List<RankingItem>, title: String, source: String, a: Int, b: Int, book: Long, rank: RankingItem) = Unit
        fun current(items: ArrayList<RankingItem>, source: String, tab: Int, book: Long, rank: RankingItem) = Unit
        fun wrongOrder(items: List<RankingItem>, source: String, book: Long, tab: Int, rank: RankingItem) = Unit
        fun wrongEntity(items: List<RankingItem>, source: String, tab: Int, book: Long, rank: Any) = Unit
        fun wrongCollection(items: Set<RankingItem>, source: String, tab: Int, book: Long, rank: RankingItem) = Unit
        companion object {
            @JvmStatic fun staticLookalike(items: List<RankingItem>, source: String, tab: Int, book: Long, rank: RankingItem) = Unit
        }
    }
    private class DeviceFixture {
        fun legacy(time: Long, qimei: String): String = ""
        fun current(time: Long, qimei: String, includeInfo: Boolean): String = ""
        fun wrongFlag(time: Long, qimei: String, includeInfo: Int): String = ""
        fun wrongOrder(qimei: String, time: Long, includeInfo: Boolean): String = ""
        companion object {
            @JvmStatic fun staticLookalike(time: Long, qimei: String, includeInfo: Boolean): String = ""
        }
    }
    private class VerifyFixture
    private class ConfigFixture
    private class FinishFixture {
        companion object {
            @JvmStatic fun renamedDialog(dialog: LoadingDialogFixture, level: AtomicInteger, process: Int,
                task: AtomicReference<Any>, verify: VerifyFixture, config: ConfigFixture, event: Pair<Any, Any>) = Unit
            @JvmStatic fun notDialog(dialog: NonDialogFixture, level: AtomicInteger, process: Int,
                task: AtomicReference<Any>, verify: VerifyFixture, config: ConfigFixture, event: Pair<Any, Any>) = Unit
            @JvmStatic fun wrongEvent(dialog: LoadingDialogFixture, level: AtomicInteger, process: Int,
                task: AtomicReference<Any>, verify: VerifyFixture, config: ConfigFixture, event: Any) = Unit
        }
    }

    @Test
    fun applicationTypefaceGetterContractUsesInterfaceTopology() {
        val regular = ApplicationTypefaceBridgeFixture::class.java.getDeclaredMethod("regular")
        val invalid = InvalidApplicationTypefaceBridgeFixture::class.java.getDeclaredMethod("regular")

        assertTrue(HostMethodContracts.isApplicationTypefaceGetter(regular))
        assertFalse(HostMethodContracts.isApplicationTypefaceGetter(invalid))
    }

    @Test
    fun flutterFontRegistrarContractUsesEngineAndPigeonApiShapes() {
        val valid = FlutterRegistrarFixture::class.java.getDeclaredMethod(
            "register",
            FakeFlutterEngine::class.java,
            NativeFontApiFixture::class.java,
        )
        val invalid = FlutterRegistrarFixture::class.java.getDeclaredMethod(
            "registerInvalid",
            FakeFlutterEngine::class.java,
            InvalidNativeFontApiFixture::class.java,
        )

        assertTrue(
            HostMethodContracts.isFlutterNativeFontRegistrar(
                valid,
                engineName = FakeFlutterEngine::class.java.name,
            ),
        )
        assertFalse(
            HostMethodContracts.isFlutterNativeFontRegistrar(
                invalid,
                engineName = FakeFlutterEngine::class.java.name,
            ),
        )
    }

    @Test
    fun userLookupSearchContractUsesPresenterTopologyInsteadOfObfuscatedNames() {
        val request = UserSearchPresenterFixture::class.java.getDeclaredMethod(
            "renamedRequest",
            String::class.java,
            android.content.ContentValues::class.java,
            Int::class.javaPrimitiveType,
        )
        val invalid = InvalidUserSearchPresenterFixture::class.java.getDeclaredMethod(
            "renamedRequest",
            String::class.java,
            android.content.ContentValues::class.java,
            Int::class.javaPrimitiveType,
        )

        assertTrue(
            MaintenanceMethodContracts.isUserLookupSearchRequest(
                request,
                presenterPackagePrefix = null,
            ),
        )
        assertFalse(
            MaintenanceMethodContracts.isUserLookupSearchRequest(
                invalid,
                presenterPackagePrefix = null,
            ),
        )
    }

    @Test
    fun notificationContractsIgnoreMethodAndCallbackShortNames() {
        val request = NotificationFixture::class.java.getDeclaredMethod(
            "renamedRequest",
            DialogManagerFixture::class.java,
            RxActivityFixture::class.java,
            CommonDialogCallbackFixture::class.java,
            String::class.java,
            Int::class.javaPrimitiveType,
            NotificationFixture.CallbackZ::class.java,
        )
        val scene = NotificationFixture::class.java.getDeclaredMethod(
            "renamedScene",
            Int::class.javaPrimitiveType,
            RxActivityFixture::class.java,
            Long::class.javaPrimitiveType,
            String::class.java,
        )

        assertTrue(
            HostMethodContracts.isNotificationDialogRequest(
                request,
                ownerName = NotificationFixture::class.java.name,
                dialogManagerName = DialogManagerFixture::class.java.name,
                rxActivityName = RxActivityFixture::class.java.name,
                commonDialogCallbackName = CommonDialogCallbackFixture::class.java.name,
            ),
        )
        assertTrue(
            HostMethodContracts.isNotificationSceneGuide(
                scene,
                ownerName = NotificationFixture::class.java.name,
                rxActivityName = RxActivityFixture::class.java.name,
            ),
        )
        assertTrue(
            HostMethodContracts.isNotificationDialogRequest(
                NotificationFixture::class.java.getDeclaredMethod(
                    "widenedRequest", DialogManagerFixture::class.java, android.app.Activity::class.java,
                    CommonDialogCallbackFixture::class.java, String::class.java,
                    Int::class.javaPrimitiveType, NotificationFixture.CallbackZ::class.java,
                ),
                ownerName = NotificationFixture::class.java.name,
                dialogManagerName = DialogManagerFixture::class.java.name,
                rxActivityName = RxActivityFixture::class.java.name,
                commonDialogCallbackName = CommonDialogCallbackFixture::class.java.name,
            ),
        )
        assertFalse(
            HostMethodContracts.isNotificationDialogRequest(
                NotificationFixture::class.java.getDeclaredMethod(
                    "invalidRequest",
                    DialogManagerFixture::class.java,
                    RxActivityFixture::class.java,
                    CommonDialogCallbackFixture::class.java,
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    InvalidNotificationCallback::class.java,
                ),
                ownerName = NotificationFixture::class.java.name,
                dialogManagerName = DialogManagerFixture::class.java.name,
                rxActivityName = RxActivityFixture::class.java.name,
                commonDialogCallbackName = CommonDialogCallbackFixture::class.java.name,
            ),
        )
    }

    @Test
    fun bookshelfBindUsesCoroutineScopeContractInsteadOfObfuscatedTypeName() {
        val method = BookshelfFixture::class.java.getDeclaredMethod(
            "renamedBind",
            BindingFixture::class.java,
            Int::class.javaPrimitiveType,
            ShelfItemFixture::class.java,
            ScopeZ::class.java,
            Int::class.javaPrimitiveType,
        )

        assertTrue(
            HostMethodContracts.isBookshelfNewListBookBind(
                method,
                ownerName = BookshelfFixture::class.java.name,
                bindingName = BindingFixture::class.java.name,
                shelfItemName = ShelfItemFixture::class.java.name,
                coroutineContextName = CoroutineContextFixture::class.java.name,
            ),
        )
    }

    @Test
    fun sanJiangSetterUsesRetainedItemGenericInsteadOfShortMethodName() {
        val books = SanJiangAdapterFixture::class.java.getDeclaredMethod(
            "renamedBooks",
            List::class.java,
        )
        val headers = SanJiangAdapterFixture::class.java.getDeclaredMethod(
            "renamedHeaders",
            List::class.java,
        )

        assertTrue(
            HostMethodContracts.isSanJiangBookListSetter(
                books,
                ownerName = SanJiangAdapterFixture::class.java.name,
                itemName = SanJiangStoreItemFixture::class.java.name,
            ),
        )
        assertFalse(
            HostMethodContracts.isSanJiangBookListSetter(
                headers,
                ownerName = SanJiangAdapterFixture::class.java.name,
                itemName = SanJiangStoreItemFixture::class.java.name,
            ),
        )
    }

    @Test
    fun welfareFinishWatchAcceptsAuditedSixAndSevenArgumentShapes() {
        val oldShape = WelfareFinishWatchFixture::class.java.getDeclaredMethod(
            "oldShape",
            WelfareDialogFixture::class.java,
            java.util.concurrent.atomic.AtomicInteger::class.java,
            Int::class.javaPrimitiveType,
            java.util.concurrent.atomic.AtomicReference::class.java,
            WelfareVerifierFixture::class.java,
            android.util.Pair::class.java,
        )
        val newShape = WelfareFinishWatchFixture::class.java.getDeclaredMethod(
            "newShape",
            WelfareDialogFixture::class.java,
            java.util.concurrent.atomic.AtomicInteger::class.java,
            Int::class.javaPrimitiveType,
            java.util.concurrent.atomic.AtomicReference::class.java,
            WelfareVerifierFixture::class.java,
            WelfareRewardConfigFixture::class.java,
            android.util.Pair::class.java,
        )
        val invalid = WelfareFinishWatchFixture::class.java.getDeclaredMethod(
            "invalidShape",
            WelfareDialogFixture::class.java,
            java.util.concurrent.atomic.AtomicInteger::class.java,
            Int::class.javaPrimitiveType,
            java.util.concurrent.atomic.AtomicReference::class.java,
            WelfareVerifierFixture::class.java,
            String::class.java,
            android.util.Pair::class.java,
        )
        val verify: (java.lang.reflect.Method) -> Boolean = { method ->
            HostMethodContracts.isWelfareFinishWatchBusiness(
                method,
                ownerName = "legacy.exact.owner",
                ownerPackagePrefix = WelfareFinishWatchFixture::class.java.packageName + ".",
                dialogName = WelfareDialogFixture::class.java.name,
                verifierName = WelfareVerifierFixture::class.java.name,
                rewardConfigName = WelfareRewardConfigFixture::class.java.name,
            )
        }

        assertTrue(verify(oldShape))
        assertTrue(verify(newShape))
        assertFalse(verify(invalid))
    }

    @Test
    fun profileContractsIgnoreObfuscatedOwnerCallbackAndMethodNames() {
        val convert = ProfileContractFixture::class.java.getDeclaredMethod(
            "z",
            ProfileContractFixture.Response::class.java,
            Boolean::class.javaPrimitiveType,
        )
        val chapter = ProfileContractFixture::class.java.getDeclaredMethod(
            "x",
            android.content.Context::class.java,
            Long::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            ProfileContractFixture.CallbackBase::class.java,
        )
        val callback = ProfileContractFixture.callback()
        val success = callback.javaClass.getDeclaredMethod(
            "b",
            org.json.JSONObject::class.java,
            String::class.java,
            Int::class.javaPrimitiveType,
        )
        val unrelatedSuccess = UnrelatedProfileCallbackFixture.callback().javaClass.getDeclaredMethod(
            "b",
            org.json.JSONObject::class.java,
            String::class.java,
            Int::class.javaPrimitiveType,
        )

        assertTrue(
            HostMethodContracts.isProfileHomeResponseConvert(
                convert,
                serverResponseName = ProfileContractFixture.Response::class.java.name,
                homePageDataName = ProfileContractFixture.HomeData::class.java.name,
                homePageItemName = ProfileContractFixture.HomeItem::class.java.name,
            ),
        )
        assertTrue(
            HostMethodContracts.isProfileSecondPageApi(
                chapter,
                callbackBaseName = ProfileContractFixture.CallbackBase::class.java.name,
            ),
        )
        assertTrue(
            HostMethodContracts.isProfilePreloadCallbackSuccess(
                success,
                serverResponseName = ProfileContractFixture.Response::class.java.name,
                homePageDataName = ProfileContractFixture.HomeData::class.java.name,
                homePageItemName = ProfileContractFixture.HomeItem::class.java.name,
            ),
        )
        assertFalse(
            HostMethodContracts.isProfileSecondPageApi(
                ProfileContractFixture::class.java.getDeclaredMethod(
                    "invalid",
                    android.content.Context::class.java,
                    Long::class.javaPrimitiveType,
                    ProfileContractFixture.CallbackBase::class.java,
                ),
            ),
        )
        assertFalse(
            HostMethodContracts.isProfilePreloadCallbackSuccess(
                unrelatedSuccess,
                serverResponseName = ProfileContractFixture.Response::class.java.name,
                homePageDataName = ProfileContractFixture.HomeData::class.java.name,
                homePageItemName = ProfileContractFixture.HomeItem::class.java.name,
            ),
        )
    }

    @Test
    fun profileRetrofitContractsUseWirePathPayloadAndObservableShape() {
        val follow = ProfileRetrofitFixture::class.java.getDeclaredMethod(
            "renamedFollow",
            Long::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
        val audio = ProfileRetrofitFixture::class.java.getDeclaredMethod(
            "renamedAudio",
            Long::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
        val invalidAudio = ProfileRetrofitFixture::class.java.getDeclaredMethod(
            "invalidAudio",
            Long::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )

        assertTrue(
            HostMethodContracts.isProfileUserFollowApi(
                follow,
                serverResponseName = ProfileWireResponse::class.java.name,
                userFollowBeanName = ProfileFollowBean::class.java.name,
            ),
        )
        assertTrue(
            HostMethodContracts.isProfileUserAudioListApi(
                audio,
                serverResponseName = ProfileWireResponse::class.java.name,
                audioInfoBeanName = ProfileAudioBean::class.java.name,
            ),
        )
        assertFalse(
            HostMethodContracts.isProfileUserAudioListApi(
                invalidAudio,
                serverResponseName = ProfileWireResponse::class.java.name,
                audioInfoBeanName = ProfileAudioBean::class.java.name,
            ),
        )
    }

    @Test
    fun profileCapsuleContractUsesExactPathAndRxPayload() {
        val capsule = ProfileCapsuleFixture::class.java.getDeclaredMethod(
            "renamedCapsule",
            Long::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
        val wrongPath = ProfileCapsuleFixture::class.java.getDeclaredMethod(
            "wrongPath",
            Long::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
        val wrongPayload = ProfileCapsuleFixture::class.java.getDeclaredMethod(
            "wrongPayload",
            Long::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )

        assertTrue(
            HostMethodContracts.isProfileCapsuleApi(
                capsule,
                serverResponseName = CapsuleServerResponse::class.java.name,
                capsuleEntityName = CapsuleEntity::class.java.name,
                observableName = "io.reactivex.r",
            ),
        )
        assertFalse(
            HostMethodContracts.isProfileCapsuleApi(
                wrongPath,
                serverResponseName = CapsuleServerResponse::class.java.name,
                capsuleEntityName = CapsuleEntity::class.java.name,
                observableName = "io.reactivex.r",
            ),
        )
        assertFalse(
            HostMethodContracts.isProfileCapsuleApi(
                wrongPayload,
                serverResponseName = CapsuleServerResponse::class.java.name,
                capsuleEntityName = CapsuleEntity::class.java.name,
                observableName = "io.reactivex.r",
            ),
        )
    }

    @Test
    fun profileCvContractUsesContinuationPayloadAndRejectsShapeDrift() {
        val cv = ProfileCvFixture::class.java.getDeclaredMethod(
            "renamedCv",
            String::class.java,
            String::class.java,
            Continuation::class.java,
        )
        val wrongContinuation = ProfileCvFixture::class.java.getDeclaredMethod(
            "wrongContinuation",
            String::class.java,
            String::class.java,
            String::class.java,
        )
        val wrongPayload = ProfileCvFixture::class.java.getDeclaredMethod(
            "wrongPayload",
            String::class.java,
            String::class.java,
            Continuation::class.java,
        )

        assertTrue(
            HostMethodContracts.isProfileCvApi(
                cv,
                serverResponseName = CvServerResponse::class.java.name,
                payloadName = CvPayload::class.java.name,
            ),
        )
        assertFalse(
            HostMethodContracts.isProfileCvApi(
                wrongContinuation,
                serverResponseName = CvServerResponse::class.java.name,
                payloadName = CvPayload::class.java.name,
            ),
        )
        assertFalse(
            HostMethodContracts.isProfileCvApi(
                wrongPayload,
                serverResponseName = CvServerResponse::class.java.name,
                payloadName = CvPayload::class.java.name,
            ),
        )
    }

    @Test
    fun autoInvestContractsUseStableCallbackAndWireShapes() {
        val detail = AutoInvestLegacyFixture::class.java.getDeclaredMethod(
            "renamedDetail",
            android.content.Context::class.java,
            Long::class.javaPrimitiveType,
            AutoInvestCallbackBase::class.java,
        )
        val invalidDetail = AutoInvestLegacyFixture::class.java.getDeclaredMethod(
            "invalidDetail",
            android.content.Context::class.java,
            Long::class.javaPrimitiveType,
            InvalidAutoInvestCallbackBase::class.java,
        )
        val adapter = AutoInvestLegacyFixture::class.java.getDeclaredMethod(
            "renamedAdapter",
            AutoInvestCallbackFactory::class.java,
            java.lang.reflect.Type::class.java,
            Boolean::class.javaPrimitiveType,
        )
        val request = AutoInvestRetrofitFixture::class.java.getDeclaredMethod(
            "renamedRequest",
            Long::class.javaPrimitiveType,
            String::class.java,
            Int::class.javaPrimitiveType,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
        )
        val invalidRequest = AutoInvestRetrofitFixture::class.java.getDeclaredMethod(
            "invalidRequest",
            Long::class.javaPrimitiveType,
            String::class.java,
            Int::class.javaPrimitiveType,
        )
        val readerBookId = AutoInvestReaderFixture::class.java.getDeclaredMethod("getBookId")

        assertTrue(HostMethodContracts.isNewBookInvestDetailApi(detail))
        assertFalse(HostMethodContracts.isNewBookInvestDetailApi(invalidDetail))
        assertTrue(
            HostMethodContracts.isLegacyRxHttpAdapter(
                adapter,
                observableName = io.reactivex.r::class.java.name,
            ),
        )
        assertTrue(
            HostMethodContracts.isNewBookInvestRequestApi(
                request,
                observableName = io.reactivex.r::class.java.name,
            ),
        )
        assertFalse(
            HostMethodContracts.isNewBookInvestRequestApi(
                invalidRequest,
                observableName = io.reactivex.r::class.java.name,
            ),
        )
        assertTrue(
            HostMethodContracts.isReaderBookIdGetter(
                readerBookId,
                ownerName = AutoInvestReaderFixture::class.java.name,
            ),
        )
    }

    private class DialogManagerFixture
    private class RxActivityFixture
    private class CommonDialogCallbackFixture
    private interface InvalidNotificationCallback {
        fun onlyOne(value: Boolean)
    }

    private interface UserSearchViewFixture {
        fun onLoadSuccess(value: org.json.JSONObject)
        fun onFixResultData(value: org.json.JSONObject)
        fun onLoadFailed(message: String)
        fun onFirstList(items: List<Any>)
        fun onSecondList(items: List<Any>)
        fun onThirdList(items: List<Any>)
        fun onFilters(first: org.json.JSONArray, second: org.json.JSONArray)
        fun onOrder(value: Int)
        fun onBanner(value: Boolean)
    }

    private class UserSearchPresenterFixture(
        context: android.content.Context,
        view: UserSearchViewFixture,
    ) : android.os.Handler.Callback {
        fun renamedRequest(keyword: String, values: android.content.ContentValues, type: Int) = Unit

        fun renamedResult(json: org.json.JSONObject, keyword: String, type: Int, page: Int) = Unit

        override fun handleMessage(message: android.os.Message): Boolean = false
    }

    private class InvalidUserSearchPresenterFixture {
        fun renamedRequest(keyword: String, values: android.content.ContentValues, type: Int) = Unit
    }

    private class NotificationFixture {
        interface CallbackZ {
            fun first(value: Boolean)
            fun second(value: Boolean)
            fun third(value: Boolean)
        }

        companion object {
            @JvmStatic
            fun renamedRequest(
                dialog: DialogManagerFixture,
                activity: RxActivityFixture,
                commonCallback: CommonDialogCallbackFixture,
                source: String,
                scene: Int,
                callback: CallbackZ,
            ) = Unit

            @JvmStatic
            fun widenedRequest(
                dialog: DialogManagerFixture,
                activity: android.app.Activity,
                commonCallback: CommonDialogCallbackFixture,
                source: String,
                scene: Int,
                callback: CallbackZ,
            ) = Unit

            @JvmStatic
            fun invalidRequest(
                dialog: DialogManagerFixture,
                activity: RxActivityFixture,
                commonCallback: CommonDialogCallbackFixture,
                source: String,
                scene: Int,
                callback: InvalidNotificationCallback,
            ) = Unit

            @JvmStatic
            fun renamedScene(scene: Int, activity: RxActivityFixture, bookId: Long, source: String): Boolean = false
        }
    }

    private class BindingFixture
    private class ShelfItemFixture
    private class CoroutineContextFixture
    private interface ScopeZ {
        fun getCoroutineContext(): CoroutineContextFixture
    }

    private class BookshelfFixture {
        fun renamedBind(
            binding: BindingFixture,
            position: Int,
            item: ShelfItemFixture,
            scope: ScopeZ,
            payload: Int,
        ) = Unit
    }

    private class SanJiangStoreItemFixture
    private class SanJiangHeaderItemFixture
    private class SanJiangAdapterFixture {
        fun renamedBooks(items: List<SanJiangStoreItemFixture>) = Unit
        fun renamedHeaders(items: List<SanJiangHeaderItemFixture>) = Unit
    }

    private class WelfareDialogFixture
    private class WelfareVerifierFixture
    private class WelfareRewardConfigFixture
    private class WelfareFinishWatchFixture {
        companion object {
            @JvmStatic
            fun oldShape(
                dialog: WelfareDialogFixture,
                count: java.util.concurrent.atomic.AtomicInteger,
                index: Int,
                result: java.util.concurrent.atomic.AtomicReference<*>,
                verifier: WelfareVerifierFixture,
                pair: android.util.Pair<*, *>,
            ) = Unit

            @JvmStatic
            fun newShape(
                dialog: WelfareDialogFixture,
                count: java.util.concurrent.atomic.AtomicInteger,
                index: Int,
                result: java.util.concurrent.atomic.AtomicReference<*>,
                verifier: WelfareVerifierFixture,
                config: WelfareRewardConfigFixture,
                pair: android.util.Pair<*, *>,
            ) = Unit

            @JvmStatic
            fun invalidShape(
                dialog: WelfareDialogFixture,
                count: java.util.concurrent.atomic.AtomicInteger,
                index: Int,
                result: java.util.concurrent.atomic.AtomicReference<*>,
                verifier: WelfareVerifierFixture,
                config: String,
                pair: android.util.Pair<*, *>,
            ) = Unit
        }
    }

    private class ProfileWireResponse<T>
    private class ProfileFollowBean
    private class ProfileAudioBean
    private interface NoBlockingObservable<T>

    private class CapsuleServerResponse<T>
    private class CapsuleEntity

    private interface ProfileCapsuleFixture {
        @retrofit2.http.GET("/argus/api/v1/capsulenovel/createByUser")
        fun renamedCapsule(userId: Long, page: Int, pageSize: Int): io.reactivex.r<CapsuleServerResponse<CapsuleEntity>>

        @retrofit2.http.GET("/argus/api/v1/capsulenovel/wrong")
        fun wrongPath(userId: Long, page: Int, pageSize: Int): io.reactivex.r<CapsuleServerResponse<CapsuleEntity>>

        @retrofit2.http.GET("/argus/api/v1/capsulenovel/createByUser")
        fun wrongPayload(userId: Long, page: Int, pageSize: Int): io.reactivex.r<CapsuleServerResponse<ProfileAudioBean>>
    }

    private class CvServerResponse<T>
    private class CvPayload
    private class CvWrongPayload

    private interface ProfileCvFixture {
        @retrofit2.http.GET("/argus/api/v1/audio/queryAnchorAudioPageList")
        fun renamedCv(
            pageNo: String,
            anchorId: String,
            continuation: Continuation<CvServerResponse<CvPayload>>,
        ): Any

        @retrofit2.http.GET("/argus/api/v1/audio/queryAnchorAudioPageList")
        fun wrongContinuation(pageNo: String, anchorId: String, continuation: String): Any

        @retrofit2.http.GET("/argus/api/v1/audio/queryAnchorAudioPageList")
        fun wrongPayload(
            pageNo: String,
            anchorId: String,
            continuation: Continuation<CvServerResponse<CvWrongPayload>>,
        ): Any
    }

    private class AutoInvestHttpResponse

    private abstract class AutoInvestCallbackBase {
        abstract fun onSuccess(response: AutoInvestHttpResponse)
        abstract fun onError(response: AutoInvestHttpResponse)
    }

    private abstract class InvalidAutoInvestCallbackBase {
        abstract fun onSuccess(response: AutoInvestHttpResponse)
    }

    private fun interface AutoInvestCallbackFactory {
        fun create(callback: AutoInvestCallbackBase)
    }

    private class AutoInvestLegacyFixture {
        companion object {
            @JvmStatic
            fun renamedDetail(
                context: android.content.Context,
                bookId: Long,
                callback: AutoInvestCallbackBase,
            ) = Unit

            @JvmStatic
            fun invalidDetail(
                context: android.content.Context,
                bookId: Long,
                callback: InvalidAutoInvestCallbackBase,
            ) = Unit

            @JvmStatic
            fun renamedAdapter(
                factory: AutoInvestCallbackFactory,
                type: java.lang.reflect.Type,
                completeAfterSuccess: Boolean,
            ): io.reactivex.r<Any> = throw UnsupportedOperationException()
        }
    }

    private interface AutoInvestRetrofitFixture {
        @retrofit2.http.GET("argus/api/v1/newbook/doinvestbook/risk")
        fun renamedRequest(
            bookId: Long,
            sessionKey: String,
            banId: Int,
            captchaTicket: String,
            captchaRandStr: String,
            challenge: String,
            validate: String,
            seccode: String,
        ): io.reactivex.r<Any>

        @retrofit2.http.GET("argus/api/v1/newbook/doinvestbook/risk")
        fun invalidRequest(bookId: Long, sessionKey: String, banId: Int): io.reactivex.r<Any>
    }

    private class AutoInvestReaderFixture {
        fun getBookId(): Long = 123L
    }

    private interface ProfileRetrofitFixture {
        @retrofit2.http.GET("/argus/api/v1/dynamic/getByUser")
        fun renamedFollow(userId: Long, page: Int, pageSize: Int): retrofit2.Call<ProfileWireResponse<ProfileFollowBean>>

        @retrofit2.http.GET("/argus/api/v1/user/getuserpageinfoaudiolist")
        fun renamedAudio(userId: Long, page: Int, pageSize: Int): io.reactivex.r<ProfileWireResponse<ProfileAudioBean>>

        @retrofit2.http.GET("/argus/api/v1/user/getuserpageinfoaudiolist")
        fun invalidAudio(userId: Long, page: Int, pageSize: Int): NoBlockingObservable<ProfileWireResponse<ProfileAudioBean>>
    }

    private interface ApplicationTypefaceContractFixture {
        fun regular(): Typeface
        fun emphasis(): Typeface
        fun unrelated(): String
    }

    private class ApplicationTypefaceBridgeFixture : ApplicationTypefaceContractFixture {
        override fun regular(): Typeface = throw UnsupportedOperationException()
        override fun emphasis(): Typeface = throw UnsupportedOperationException()
        override fun unrelated(): String = ""
    }

    private interface InvalidApplicationTypefaceContractFixture {
        fun regular(): Typeface
    }

    private class InvalidApplicationTypefaceBridgeFixture : InvalidApplicationTypefaceContractFixture {
        override fun regular(): Typeface = throw UnsupportedOperationException()
    }

    private class FakeFlutterEngine

    private fun interface NativeFontApiFixture {
        fun loadNativeFont(name: String): ByteBuffer
    }

    private interface InvalidNativeFontApiFixture {
        fun loadNativeFont(name: String): ByteBuffer
        fun extra(): String
    }

    private class FlutterRegistrarFixture {
        companion object {
            @JvmStatic
            fun register(engine: FakeFlutterEngine, api: NativeFontApiFixture) = Unit

            @JvmStatic
            fun registerInvalid(engine: FakeFlutterEngine, api: InvalidNativeFontApiFixture) = Unit
        }
    }
}
