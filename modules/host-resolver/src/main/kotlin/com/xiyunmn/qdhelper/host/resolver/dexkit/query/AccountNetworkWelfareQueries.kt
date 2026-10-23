package com.xiyunmn.qdhelper.host.resolver.dexkit.query

import android.app.Activity
import android.content.Context
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import org.luckypray.dexkit.query.enums.StringMatchType
import com.xiyunmn.qdhelper.host.resolver.reflection.ReflectionAccess
import java.lang.reflect.Modifier

internal object AccountNetworkWelfareQueries {
    private const val REWARD_VIDEO_CONFIG_CLASS =
        "com.qidian.QDReader.repository.entity.config.RewardVideoConfig"

    /**
     * Host auto-update entry class (h/i/m/n/o), which carries the "发现新版本" popup. Its obfuscated
     * short name drifts inside the active interval, so anchor it version-independently by an
     * update-notify string constant plus
     * the class shape: an inner interface (~11 methods) plus static void/boolean methods carrying that
     * interface at any parameter position. The resolved method's declaringClass is the auto-update class.
     */
    val autoUpdateEntryAnchor = DexKitMethodQuery(
        id = "dialog.update.auto_update_entry",
        purpose = "auto-update entry class via update-notify string + judian-interface shape",
        query = {
            matcher {
                addUsingString("SettingUpdateVersionNotifyId_", StringMatchType.Contains)
            }
        },
        verify = { method ->
            val p = method.parameterTypes
            Modifier.isPrivate(method.modifiers) && Modifier.isStatic(method.modifiers) &&
                method.returnType == Void.TYPE && p.size == 5 &&
                p[0] == Activity::class.java && p[1] == Boolean::class.javaPrimitiveType &&
                p[2] == android.os.Handler::class.java && p[3].isInterface &&
                p[3].enclosingClass == method.declaringClass && p[4] == Boolean::class.javaPrimitiveType &&
                hasAutoUpdateJudianShape(method.declaringClass)
        },
    )

    val updatePresenter = DexKitMethodQuery(
        id = "dialog.update.presenter",
        purpose = "update presenter via manual-version analytics and callback contract",
        query = {
            searchPackages("com.qidian.QDReader.other")
            matcher {
                returnType(Void.TYPE)
                paramTypes(Context::class.java.name, String::class.java.name, String::class.java.name, "int")
                addUsingString("newversion", StringMatchType.Equals)
            }
        },
        verify = { method ->
            !Modifier.isStatic(method.modifiers) && method.returnType == Void.TYPE &&
                method.parameterTypes.contentEquals(arrayOf(Context::class.java, String::class.java, String::class.java, Int::class.javaPrimitiveType)) &&
                method.declaringClass.interfaces.any { callback ->
                    callback.enclosingClass?.let(::hasAutoUpdateJudianShape) == true
                } &&
                ReflectionAccess.fieldsDeclaredBy(method.declaringClass).any { Activity::class.java.isAssignableFrom(it.type) }
        },
    )

    val commonDeviceQimei16 = semanticStaticStringGetterQuery(
        id = "network.identity.common_device.qimei16",
        purpose = "common device aggregate qimei16 getter (semantic static contract)",
        searchPackage = "com.qidian.common.lib",
        anchor = "getQIMEI: get local qimei16 = ",
        ownerName = HostSymbols.Classes.COMMON_LIB_DEVICE,
    )

    val commonDeviceQimei36 = semanticStaticStringGetterQuery(
        id = "network.identity.common_device.qimei36",
        purpose = "common device aggregate qimei36 getter (semantic static contract)",
        searchPackage = "com.qidian.common.lib",
        anchor = "getQIMEI36: get local qimei36 = ",
        ownerName = HostSymbols.Classes.COMMON_LIB_DEVICE,
    )

    val hostOaidGetter = semanticStaticStringGetterQuery(
        id = "debug.host_oaid.getter",
        purpose = "common device OAID getter (semantic static contract via HUAWEI_OAID)",
        searchPackage = "com.qidian.common.lib",
        anchor = "HUAWEI_OAID",
        ownerName = HostSymbols.Classes.COMMON_LIB_DEVICE,
    )

    // Name-independent cached-qimei getters on CommonDevice (com.qidian.common.lib.d). The host caches the
    // Beacon/OStar qimei into its own prefs and reads them back through single-letter static getters whose
    // letters drift across versions (7.9.469 P/Q/R/S, 7.9.470 O/P/Q/R). Each getter bakes in its STABLE
    // pref-key string, so anchor on that key plus the public static no-arg -> String contract. The class's
    // void clear() method also references these keys but is filtered out by the return/parameter verifier.
    val commonDeviceCachedBeacon16 = anchoredStringGetterQuery(
        id = "network.identity.common_device_cached.beacon16",
        purpose = "common device cached qimei beacon16 getter (name-independent via pref key BEACON_QIMEI)",
        searchPackage = "com.qidian.common.lib",
        anchor = "BEACON_QIMEI",
        ownerName = HostSymbols.Classes.COMMON_LIB_DEVICE,
    )
    val commonDeviceCachedBeacon36 = anchoredStringGetterQuery(
        id = "network.identity.common_device_cached.beacon36",
        purpose = "common device cached qimei beacon36 getter (name-independent via pref key BEACON_QIMEI_36)",
        searchPackage = "com.qidian.common.lib",
        anchor = "BEACON_QIMEI_36",
        ownerName = HostSymbols.Classes.COMMON_LIB_DEVICE,
    )
    val commonDeviceCachedOstar16 = anchoredStringGetterQuery(
        id = "network.identity.common_device_cached.ostar16",
        purpose = "common device cached qimei ostar16 getter (name-independent via pref key O_STAR_Q_IMEI_16)",
        searchPackage = "com.qidian.common.lib",
        anchor = "O_STAR_Q_IMEI_16",
        ownerName = HostSymbols.Classes.COMMON_LIB_DEVICE,
    )
    val commonDeviceCachedOstar36 = anchoredStringGetterQuery(
        id = "network.identity.common_device_cached.ostar36",
        purpose = "common device cached qimei ostar36 getter (name-independent via pref key O_STAR_Q_IMEI_36)",
        searchPackage = "com.qidian.common.lib",
        anchor = "O_STAR_Q_IMEI_36",
        ownerName = HostSymbols.Classes.COMMON_LIB_DEVICE,
    )

    // Name-independent fake-identity getters on FakeIdentityHelper (com.qidian.common.lib.util.h0). Each
    // getter reads a spoofable value back from host prefs by a STABLE key string baked into the method; the
    // single-letter names (7.9.469 a/b/d/e/f/g) drift, so anchor on key + shape. Verified in decompile:
    // a=KEY_FAKE_LOCAL_QIMEI, b=KEY_FAKE_LOCAL_QIMEI36, d=KEY_FAKE_OSTAR, e=KEY_FAKE_OSTAR36,
    // f=KEY_FAKE_QIMEI, g=KEY_FAKE_QIMEI36. The class's setters/clear reference the same keys but are
    // filtered out by the no-arg/String-return verify.
    val fakeIdentityLocal16 = anchoredStringGetterQuery(
        id = "network.identity.fake_identity.fake_local16",
        purpose = "fake identity fake_local16 getter (name-independent via pref key KEY_FAKE_LOCAL_QIMEI)",
        searchPackage = "com.qidian.common.lib.util",
        anchor = "KEY_FAKE_LOCAL_QIMEI",
        ownerName = HostSymbols.Classes.COMMON_LIB_FAKE_IDENTITY,
        requireStatic = false,
    )
    val fakeIdentityLocal36 = anchoredStringGetterQuery(
        id = "network.identity.fake_identity.fake_local36",
        purpose = "fake identity fake_local36 getter (name-independent via pref key KEY_FAKE_LOCAL_QIMEI36)",
        searchPackage = "com.qidian.common.lib.util",
        anchor = "KEY_FAKE_LOCAL_QIMEI36",
        ownerName = HostSymbols.Classes.COMMON_LIB_FAKE_IDENTITY,
        requireStatic = false,
    )
    val fakeIdentityOstar16 = anchoredStringGetterQuery(
        id = "network.identity.fake_identity.fake_ostar16",
        purpose = "fake identity fake_ostar16 getter (name-independent via pref key KEY_FAKE_OSTAR)",
        searchPackage = "com.qidian.common.lib.util",
        anchor = "KEY_FAKE_OSTAR",
        ownerName = HostSymbols.Classes.COMMON_LIB_FAKE_IDENTITY,
        requireStatic = false,
    )
    val fakeIdentityOstar36 = anchoredStringGetterQuery(
        id = "network.identity.fake_identity.fake_ostar36",
        purpose = "fake identity fake_ostar36 getter (name-independent via pref key KEY_FAKE_OSTAR36)",
        searchPackage = "com.qidian.common.lib.util",
        anchor = "KEY_FAKE_OSTAR36",
        ownerName = HostSymbols.Classes.COMMON_LIB_FAKE_IDENTITY,
        requireStatic = false,
    )
    val fakeIdentityQimei16 = anchoredStringGetterQuery(
        id = "network.identity.fake_identity.fake16",
        purpose = "fake identity fake16 getter (name-independent via pref key KEY_FAKE_QIMEI)",
        searchPackage = "com.qidian.common.lib.util",
        anchor = "KEY_FAKE_QIMEI",
        ownerName = HostSymbols.Classes.COMMON_LIB_FAKE_IDENTITY,
        requireStatic = false,
    )
    val fakeIdentityQimei36 = anchoredStringGetterQuery(
        id = "network.identity.fake_identity.fake36",
        purpose = "fake identity fake36 getter (name-independent via pref key KEY_FAKE_QIMEI36)",
        searchPackage = "com.qidian.common.lib.util",
        anchor = "KEY_FAKE_QIMEI36",
        ownerName = HostSymbols.Classes.COMMON_LIB_FAKE_IDENTITY,
        requireStatic = false,
    )

    // 7.9.480 widens RxAppCompatActivity to Activity. Verify both audited types and the
    // inner callback structurally; neither method nor callback short names are stable.
    val notificationDialogRequest = DexKitMethodQuery(
        id = "dialog.notification.request",
        purpose = "notification permission dialog request gate (name-independent exact contract)",
        query = {
            searchPackages("com.qidian.QDReader.util")
            matcher {
                declaredClass(HostSymbols.Classes.NOTIFICATION_PERMISSION_UTIL)
                modifiers(Modifier.PUBLIC or Modifier.STATIC)
                paramTypes(
                    HostSymbols.Classes.QD_DIALOG_MANAGER,
                    null,
                    HostSymbols.Classes.COMMON_LIB_DIALOG_CALLBACK,
                    String::class.java.name,
                    "int",
                    null,
                )
                returnType(Void.TYPE)
            }
        },
        verify = HostMethodContracts::isNotificationDialogRequest,
    )

    // The method letter drifts (7.9.469 F -> 7.9.470 C); the full readable signature is stable.
    val notificationSceneGuide = DexKitMethodQuery(
        id = "dialog.notification.scene_guide",
        purpose = "notification permission scene guide gate (name-independent exact contract)",
        query = {
            searchPackages("com.qidian.QDReader.util")
            matcher {
                declaredClass(HostSymbols.Classes.NOTIFICATION_PERMISSION_UTIL)
                modifiers(Modifier.PUBLIC or Modifier.STATIC)
                paramTypes(
                    "int",
                    HostSymbols.Classes.RX_APP_COMPAT_ACTIVITY,
                    "long",
                    String::class.java.name,
                )
                returnType(Boolean::class.javaPrimitiveType!!)
            }
        },
        verify = HostMethodContracts::isNotificationSceneGuide,
    )

    // Name-independent: the host obfuscates the method letter across releases,
    // so anchor on the stable GDTForQD class plus the 8-arg reward-observable signature
    // (Activity, String, String, RewardVideoConfig, boolean, JSONObject, JSONArray, String).
    val gdtForQdRewardObservable = DexKitMethodQuery(
        id = "welfare.gdt_for_qd.reward_observable",
        purpose = "GDTForQD reward observable method (name-independent)",
        query = {
            searchPackages("com.qidian.QDReader.extras")
            matcher {
                paramCount(8)
            }
        },
        verify = { method ->
            method.declaringClass.name == HostSymbols.Classes.GDT_FOR_QD &&
                method.parameterTypes.size == 8 &&
                Activity::class.java.isAssignableFrom(method.parameterTypes[0]) &&
                method.parameterTypes[1] == String::class.java &&
                method.parameterTypes[2] == String::class.java &&
                method.parameterTypes[3].name == REWARD_VIDEO_CONFIG_CLASS &&
                method.parameterTypes[4] == Boolean::class.javaPrimitiveType &&
                method.parameterTypes[7] == String::class.java
        },
    )

    val welfareFlutterMethodCall = namedMethod(
        id = "welfare.flutter.method_call",
        purpose = "WelfareFlutterActivity method-call bridge",
        searchPackage = "com.qidian.QDReader.flutter",
        methodName = "doMethodCall",
        paramCount = 2,
        returnType = Void.TYPE,
        ownerName = HostSymbols.Classes.WELFARE_FLUTTER_ACTIVITY,
        verifyExtra = { method ->
            method.parameterTypes.getOrNull(0)?.name == "io.flutter.plugin.common.MethodCall" &&
                method.parameterTypes.getOrNull(1)?.name == "io.flutter.plugin.common.MethodChannel\$Result"
        },
    )

    val welfareFlutterShowGdtAd = namedMethod(
        id = "welfare.flutter.show_gdt_ad",
        purpose = "WelfareFlutterActivity GDT reward entry",
        searchPackage = "com.qidian.QDReader.flutter",
        methodName = "showGdtAD",
        paramCount = 3,
        returnType = Void.TYPE,
        verifyExtra = { method ->
            method.parameterTypes.getOrNull(0)?.let(Activity::class.java::isAssignableFrom) == true &&
                method.parameterTypes.getOrNull(1) == String::class.java &&
                method.parameterTypes.getOrNull(2) == String::class.java
        },
    )

    val qqMiniRewardLoadAd = namedMethod(
        id = "welfare.qqmini.reward.load_ad",
        purpose = "QQMini reward video load_ad",
        searchPackage = "com.tencent.qqmini.ad.proxyimpl",
        methodName = "loadAD",
        paramCount = 1,
        returnType = Void.TYPE,
        verifyExtra = { method ->
            method.declaringClass.name == HostSymbols.Classes.QQMINI_AD_REWARD_VIDEO_VIEW &&
                method.parameterTypes.contentEquals(arrayOf(Context::class.java))
        },
    )

    val qqMiniRewardShowAd = namedMethod(
        id = "welfare.qqmini.reward.show_ad",
        purpose = "QQMini reward video show_ad",
        searchPackage = "com.tencent.qqmini.ad.proxyimpl",
        methodName = "showAD",
        paramCount = 2,
        returnType = Void.TYPE,
        verifyExtra = { method ->
            method.declaringClass.name == HostSymbols.Classes.QQMINI_AD_REWARD_VIDEO_VIEW &&
                method.parameterTypes.contentEquals(arrayOf(Context::class.java, String::class.java))
        },
    )

    val tangramRewardShowAd = namedMethod(
        id = "welfare.tangram.reward.show_ad",
        purpose = "TangramRewardAD show entry",
        searchPackage = "com.qq.e.tg.rewardAD",
        methodName = "showAD",
        paramCount = 0,
        returnType = Void.TYPE,
    )

    // Name-independent H5 reward-bridge rules. The host obfuscates both the plugin class and method
    // letters across releases, so anchor on
    // the stable WebViewPlugin superclass plus each method's unique parameter signature. Each shape
    // was verified unique within the com.qidian.QDReader.webview.plugins package for its param count.

    // Reward plugin: void(int, String, RewardVideoConfig, String, String) — RewardVideoConfig at index 2.
    val h5RewardBridgeConfigMid = h5RewardBridgeQuery(
        id = "welfare.h5.reward_bridge.f1_config_mid",
        purpose = "H5 reward bridge f1_config_mid (name-independent)",
        parameters = listOf("int", "java.lang.String", REWARD_VIDEO_CONFIG_CLASS, "java.lang.String", "java.lang.String"),
        resultType = "void",
    ) { method ->
        method.returnType == Void.TYPE &&
            method.parameterTypes[2].name == REWARD_VIDEO_CONFIG_CLASS
    }

    // Reward plugin: void(int, String, String, String, RewardVideoConfig) — RewardVideoConfig last.
    val h5RewardBridgeConfigLast = h5RewardBridgeQuery(
        id = "welfare.h5.reward_bridge.f1_config_last",
        purpose = "H5 reward bridge f1_config_last (name-independent)",
        parameters = listOf("int", "java.lang.String", "java.lang.String", "java.lang.String", REWARD_VIDEO_CONFIG_CLASS),
        resultType = "void",
    ) { method ->
        method.returnType == Void.TYPE &&
            method.parameterTypes.last().name == REWARD_VIDEO_CONFIG_CLASS
    }

    // Game plugin: int(Activity, JSONObject, int).
    val h5RewardBridgeGame = h5RewardBridgeQuery(
        id = "welfare.h5.reward_bridge.r1_game",
        purpose = "H5 reward bridge r1_game (name-independent)",
        parameters = listOf("android.app.Activity", "org.json.JSONObject", "int"),
        resultType = "int",
    ) { method ->
        method.returnType == Int::class.javaPrimitiveType &&
            Activity::class.java.isAssignableFrom(method.parameterTypes[0]) &&
            method.parameterTypes[1].name == "org.json.JSONObject" &&
            method.parameterTypes[2] == Int::class.javaPrimitiveType
    }

    // Name-independent: the host obfuscates the method letter across releases.
    // The stable event log identifies the business handler before reflection; ordered
    // verification accepts the earlier 6/7-argument and 7.9.482 9-argument variants.
    val welfareFinishWatchBusiness = DexKitMethodQuery(
        id = "welfare.extra.finish_watch_business",
        purpose = "welfare finishWatch business method (name-independent)",
        query = {
            searchPackages("com.qidian.QDReader.extras")
            matcher {
                returnType("void")
                addUsingString("upload passthrough gradientLevel: ", StringMatchType.Contains)
            }
        },
        verify = HostMethodContracts::isWelfareFinishWatchBusiness,
    )

    // Name-independent: the host QDHttpCookie class letter drifts across versions, so anchor on the
    // distinctive log string baked into the
    // instance cookie getter f() plus its shape (no-arg, returns String, declaring class exposes a
    // static self-returning singleton getter). The declaring class + its g() are derived from the hit.
    val hostCookieGetter = DexKitMethodQuery(
        id = "debug.host_cookie.getter",
        purpose = "host QDHttpCookie instance cookie getter (name-independent)",
        query = {
            matcher {
                paramCount(0)
                returnType(String::class.java)
                addUsingString("getCookies error return empty", StringMatchType.Contains)
            }
        },
        verify = { method ->
            method.parameterTypes.isEmpty() &&
                method.returnType == String::class.java &&
                ReflectionAccess.methodsDeclaredBy(method.declaringClass).any { candidate ->
                    Modifier.isStatic(candidate.modifiers) &&
                        candidate.parameterTypes.isEmpty() &&
                        candidate.returnType == method.declaringClass
                }
        },
    )

    // Name-independent: the reader Otto event-bus holder class drifts across versions, so anchor on
    // the sole host class that holds a
    // static com.qidian.common.lib.bus.SingleBusManager field and exposes a static no-arg getter
    // returning the stable-named otto Bus (com.squareup.otto.judian). The bus's post method f(Object)
    // is derived from the returned Bus instance at call time. SingleBusManager + the otto Bus type are
    // both readable (unobfuscated) names, so this holds regardless of the host wrapper's short name.
    val readerEventBusGetter = DexKitMethodQuery(
        id = "reader_font.event_bus.getter",
        purpose = "reader Otto event-bus singleton getter (name-independent)",
        query = {
            matcher {
                modifiers(Modifier.STATIC)
                paramCount(0)
                returnType("com.squareup.otto.judian")
            }
        },
        verify = { method ->
            Modifier.isStatic(method.modifiers) &&
                method.parameterTypes.isEmpty() &&
                method.returnType.name == "com.squareup.otto.judian" &&
                ReflectionAccess.fieldsDeclaredBy(method.declaringClass).any { field ->
                    Modifier.isStatic(field.modifiers) &&
                        field.type.name == "com.qidian.common.lib.bus.SingleBusManager"
                }
        },
    )

    // Name-independent: the reader menu-event class drifts across versions, so instead of chasing the
    // short name we locate the sole
    // @Subscribe handler that carries the string "EVENT_CURRENTCHAPTER" on the readable-named
    // QDSuperEngineView; its single parameter type IS the event class, from which we take the
    // String constructor to build the event to post.
    val readerMenuEventHandler = DexKitMethodQuery(
        id = "reader_font.menu_event.handler",
        purpose = "reader menu-event handler whose param type is the event class (name-independent)",
        query = {
            matcher {
                paramCount(1)
                returnType(Void.TYPE)
                addUsingString("EVENT_CURRENTCHAPTER", StringMatchType.Equals)
            }
        },
        verify = { method ->
            method.parameterTypes.size == 1 &&
                method.returnType == Void.TYPE &&
                method.declaringClass.name ==
                "com.qidian.QDReader.readerengine.view.QDSuperEngineView" &&
                runCatching {
                    method.parameterTypes[0].getConstructor(String::class.java) != null
                }.getOrDefault(false)
        },
    )

    // Name-independent: the bookshelf top-operation event class drifts across versions and is a
    // different event class from the reader's current-chapter event, so
    // so locate the sole @Subscribe handler that carries the string "EVENT_CLEAR_MAIN_TOP_OPERATION"
    // on the readable-named QDBookShelfRebornFragment; its single parameter type IS the event class,
    // from which we take the String constructor to build the event to post.
    val bookshelfTopOperationEventHandler = DexKitMethodQuery(
        id = "bookshelf.top_operation.event_handler",
        purpose = "bookshelf top-operation handler whose param type is the event class (name-independent)",
        query = {
            matcher {
                paramCount(1)
                returnType(Void.TYPE)
                addUsingString("EVENT_CLEAR_MAIN_TOP_OPERATION", StringMatchType.Equals)
            }
        },
        verify = { method ->
            method.parameterTypes.size == 1 &&
                method.returnType == Void.TYPE &&
                method.declaringClass.name ==
                "com.qidian.QDReader.ui.modules.bookshelf.QDBookShelfRebornFragment" &&
                runCatching {
                    method.parameterTypes[0].getConstructor(String::class.java) != null
                }.getOrDefault(false)
        },
    )

    val welfareTaskReadingPageApi = retrofitPathQuery(
        id = "welfare.api.get_welfare_task_reading_page_v2",
        purpose = "Retrofit API path getwelfaretaskreadingpagev2",
        path = "getwelfaretaskreadingpagev2",
    )

    val welfareFinishWatchApi = retrofitPathQuery(
        id = "welfare.api.finish_watch",
        purpose = "Retrofit API path finishWatch",
        path = "finishWatch",
    )

    val autoSignInManagerInstance = namedMethod(
        id = "account.auto_sign_in.manager.instance",
        purpose = "auto-sign-in manager instance",
        searchPackage = "com.qidian.QDReader.component.manager",
        methodName = "m",
        paramCount = 0,
        ownerName = HostSymbols.Classes.CHECK_IN_MANAGER,
        matchName = false,
        verifyExtra = { method ->
            Modifier.isPublic(method.modifiers) &&
                Modifier.isStatic(method.modifiers) &&
                method.returnType == method.declaringClass
        },
    )
    val autoSignInCheckInRequest = namedMethod(
        id = "account.auto_sign_in.manager.check_in_request",
        purpose = "auto-sign-in manager check_in_request",
        searchPackage = "com.qidian.QDReader.component.manager",
        methodName = "h",
        paramCount = 1,
        paramTypes = listOf(Context::class.java.name),
        returnTypeName = HostSymbols.Classes.RX_OBSERVABLE,
        ownerName = HostSymbols.Classes.CHECK_IN_MANAGER,
        matchName = false,
        verifyExtra = { method ->
            Modifier.isPublic(method.modifiers) &&
                !Modifier.isStatic(method.modifiers) &&
                method.parameterTypes.contentEquals(arrayOf(Context::class.java))
        },
    )
    val autoSignInAlreadyCheckedIn = DexKitMethodQuery(
        id = "account.auto_sign_in.manager.already_checked_in",
        purpose = "auto-sign-in manager already_checked_in",
        query = {
            searchPackages("com.qidian.QDReader.component.manager")
            matcher {
                declaredClass(HostSymbols.Classes.CHECK_IN_MANAGER)
                paramCount(0)
                returnType(Boolean::class.javaPrimitiveType!!)
                addUsingString("CheckInTime", StringMatchType.Equals)
                addUsingString("TodayCheckIn", StringMatchType.Equals)
            }
        },
        verify = { method ->
            method.declaringClass.name == HostSymbols.Classes.CHECK_IN_MANAGER &&
                Modifier.isPublic(method.modifiers) &&
                !Modifier.isStatic(method.modifiers) &&
                method.parameterTypes.isEmpty() &&
                method.returnType == Boolean::class.javaPrimitiveType
        },
    )
    val autoSignInMembershipEligible = DexKitMethodQuery(
        id = "account.auto_sign_in.manager.membership_eligible",
        purpose = "host membership auto-sign-in eligibility",
        query = {
            searchPackages("com.qidian.QDReader.component.manager")
            matcher {
                declaredClass(HostSymbols.Classes.CHECK_IN_MANAGER)
                modifiers(Modifier.PRIVATE)
                paramCount(0)
                returnType(Boolean::class.javaPrimitiveType!!)
                callerMethods {
                    add {
                        declaredClass(HostSymbols.Classes.CHECK_IN_MANAGER)
                        addUsingString("auto_check_in_start", StringMatchType.Equals)
                        addUsingString("auto_check_in_skip", StringMatchType.Equals)
                    }
                }
            }
        },
        verify = { method ->
            method.declaringClass.name == HostSymbols.Classes.CHECK_IN_MANAGER &&
                Modifier.isPrivate(method.modifiers) &&
                !Modifier.isStatic(method.modifiers) &&
                method.parameterTypes.isEmpty() &&
                method.returnType == Boolean::class.javaPrimitiveType
        },
    )

    val autoSignInSyncCheckedIn = namedMethod(
        id = "account.auto_sign_in.manager.sync_checked_in",
        purpose = "auto-sign-in manager sync_checked_in",
        searchPackage = "com.qidian.QDReader.component.manager",
        methodName = "G",
        paramCount = 2,
        paramTypes = listOf("boolean", "boolean"),
        returnType = Void.TYPE,
        ownerName = HostSymbols.Classes.CHECK_IN_MANAGER,
        matchName = false,
        verifyExtra = { method ->
            Modifier.isPublic(method.modifiers) &&
                !Modifier.isStatic(method.modifiers)
        },
    )

    val autoInvestDetailApi = DexKitMethodQuery(
        id = "account.auto_invest.detail_api",
        purpose = "new-book investment detail API called by NewBookInvestDetailActivity",
        query = {
            searchPackages("com.qidian.QDReader.component.api")
            matcher {
                modifiers(Modifier.PUBLIC or Modifier.STATIC)
                paramTypes(Context::class.java.name, "long", null)
                returnType(Void.TYPE)
                callerMethods {
                    add { declaredClass(HostSymbols.Classes.NEW_BOOK_INVEST_DETAIL_ACTIVITY) }
                }
            }
        },
        verify = HostMethodContracts::isNewBookInvestDetailApi,
    )

    val autoInvestLegacyRxAdapter = DexKitMethodQuery(
        id = "account.auto_invest.legacy_rx_adapter",
        purpose = "legacy QDHttp callback to Rx Observable adapter",
        query = {
            searchPackages("com.qidian.QDReader.component.rx")
            matcher {
                modifiers(Modifier.PUBLIC or Modifier.STATIC)
                paramTypes(null, java.lang.reflect.Type::class.java.name, "boolean")
                returnType(HostSymbols.Classes.RX_OBSERVABLE)
            }
        },
        verify = HostMethodContracts::isLegacyRxHttpAdapter,
    )

    val autoInvestRequestApi = retrofitExactPathQuery(
        id = "account.auto_invest.request_api",
        purpose = "new-book initial investment request with host risk control",
        path = "argus/api/v1/newbook/doinvestbook/risk",
        verifyExtra = HostMethodContracts::isNewBookInvestRequestApi,
    )

    val autoInvestReaderBookId = namedMethod(
        id = "account.auto_invest.reader_book_id",
        purpose = "current QDReaderActivity book id",
        searchPackage = "com.qidian.QDReader.ui.activity",
        methodName = "getBookId",
        paramCount = 0,
        returnType = Long::class.javaPrimitiveType,
        ownerName = HostSymbols.Classes.QD_READER_ACTIVITY,
        verifyExtra = HostMethodContracts::isReaderBookIdGetter,
    )

    val qdUserManagerIsLoggedIn = DexKitMethodQuery(
        id = "account.user_manager.is_logged_in",
        purpose = "QDUserManager is_logged_in",
        query = {
            searchPackages("com.qidian.QDReader.component.user")
            matcher {
                declaredClass(HostSymbols.Classes.QD_USER_MANAGER)
                paramCount(0)
                returnType(Boolean::class.javaPrimitiveType!!)
                invokeMethods {
                    add {
                        declaredClass(HostSymbols.Classes.QD_USER_MANAGER)
                        paramTypes()
                        returnType(HostSymbols.Classes.USER_TOKEN_ITEM)
                    }
                }
            }
        },
        verify = { method ->
            method.declaringClass.name == HostSymbols.Classes.QD_USER_MANAGER &&
                !Modifier.isStatic(method.modifiers) &&
                method.parameterTypes.isEmpty() &&
                method.returnType == Boolean::class.javaPrimitiveType
        },
    )
    val qdUserManagerUserId = DexKitMethodQuery(
        id = "account.user_manager.user_id",
        purpose = "QDUserManager user_id",
        query = {
            searchPackages("com.qidian.QDReader.component.user")
            matcher {
                declaredClass(HostSymbols.Classes.QD_USER_MANAGER)
                paramCount(0)
                returnType(Long::class.javaPrimitiveType!!)
                addUsingField {
                    declaredClass(HostSymbols.Classes.USER_TOKEN_ITEM)
                    name("UserId")
                    type("long")
                }
                invokeMethods {
                    add {
                        declaredClass(HostSymbols.Classes.QD_USER_MANAGER)
                        paramTypes()
                        returnType(HostSymbols.Classes.USER_TOKEN_ITEM)
                    }
                }
            }
        },
        verify = { method ->
            method.declaringClass.name == HostSymbols.Classes.QD_USER_MANAGER &&
                !Modifier.isStatic(method.modifiers) &&
                method.parameterTypes.isEmpty() &&
                method.returnType == Long::class.javaPrimitiveType
        },
    )
}
