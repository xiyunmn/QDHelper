package com.xiyunmn.qdhelper.host.resolver.dexkit.query

import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols.Classes
import com.xiyunmn.qdhelper.host.api.target.WelfareTargetKeys
import org.luckypray.dexkit.query.enums.StringMatchType

internal object WelfareRecoveryQueries {
    val nativeAdSource = DexKitMethodQuery(
        id = WelfareTargetKeys.nativeAdSource.id.value,
        purpose = "native reward platform selection after risk verification",
        query = {
            searchPackages("com.qidian.QDReader.extras")
            matcher {
                paramTypes("java.util.concurrent.atomic.AtomicReference", Classes.REWARD_VIDEO_CONFIG,
                    "android.app.Activity", "java.lang.String", "java.lang.String", "org.json.JSONObject",
                    Classes.UNIVERSAL_VERIFY_HELPER)
                invokeMethods {
                    add { declaredClass(Classes.REWARD_VIDEO_CONFIG); name("getAdPlatform"); paramCount(0); returnType("int") }
                }
            }
        },
        verify = { method ->
            java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                method.declaringClass.name.startsWith("com.qidian.QDReader.extras.") &&
                method.parameterTypes.map { it.name } == listOf(
                    "java.util.concurrent.atomic.AtomicReference", Classes.REWARD_VIDEO_CONFIG,
                    "android.app.Activity", "java.lang.String", "java.lang.String", "org.json.JSONObject",
                    Classes.UNIVERSAL_VERIFY_HELPER) &&
                WelfareRecoveryContracts.isObservableSource(method.returnType)
        },
    )

    val nativeBridge = DexKitMethodQuery(
        id = WelfareTargetKeys.nativeClaimBridge.id.value,
        purpose = "native welfare claim bridge with captured page callback",
        query = {
            searchPackages("com.qidian.QDReader.webview.plugins")
            matcher { paramTypes(*WelfareRecoveryContracts.nativeBridgeParameters.toTypedArray()); returnType("void") }
        },
        verify = { WelfareRecoveryContracts.isPluginBridge(it, WelfareRecoveryContracts.nativeBridgeParameters) },
    )
    val groupedBridge = DexKitMethodQuery(
        id = WelfareTargetKeys.groupedClaimBridge.id.value,
        purpose = "grouped welfare claim bridge with captured page callback",
        query = {
            searchPackages("com.qidian.QDReader.webview.plugins")
            matcher { paramTypes(*WelfareRecoveryContracts.groupedBridgeParameters.toTypedArray()); returnType("void") }
        },
        verify = { WelfareRecoveryContracts.isPluginBridge(it, WelfareRecoveryContracts.groupedBridgeParameters) },
    )
    val nativeEvent = DexKitMethodQuery(
        id = WelfareTargetKeys.nativeClaimEvent.id.value,
        purpose = "native reward event and synchronous finishWatch boundary",
        query = {
            searchPackages("com.qidian.QDReader.extras")
            matcher {
                paramTypes(*WelfareRecoveryContracts.nativeEventParameters.toTypedArray())
                addUsingString("showRewardVideoNative reward empty", StringMatchType.Contains)
            }
        },
        verify = WelfareRecoveryContracts::isNativeEvent,
    )
    val groupedEvent = DexKitMethodQuery(
        id = WelfareTargetKeys.groupedClaimEvent.id.value,
        purpose = "grouped reward event and asynchronous finishWatch boundary",
        query = {
            searchPackages("com.qidian.QDReader.extras")
            matcher { addUsingString("upload passthrough gradientLevel: ", StringMatchType.Contains) }
        },
        verify = HostMethodContracts::isWelfareFinishWatchBusiness,
    )
    val groupedError = DexKitMethodQuery(
        id = WelfareTargetKeys.groupedClaimError.id.value,
        purpose = "grouped ad error and synchronous default reward boundary",
        query = {
            searchPackages("com.qidian.QDReader.extras")
            matcher { addUsingString("ad watch failed,", StringMatchType.Contains) }
        },
        verify = WelfareRecoveryContracts::isGroupedError,
    )
    val h5Callback = DexKitMethodQuery(
        id = WelfareTargetKeys.h5Callback.id.value,
        purpose = "WebView plugin execCallback dispatch",
        query = {
            searchPackages("com.qidian.QDReader.framework.webview")
            matcher {
                paramTypes("java.lang.String", "org.json.JSONObject", "int")
                returnType("void")
                addUsingString("WebViewPlugin：webview ready to call js...func=", StringMatchType.Contains)
            }
        },
        verify = {
            it.returnType == Void.TYPE &&
                !java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                it.parameterTypes.map { type -> type.name } == listOf("java.lang.String", "org.json.JSONObject", "int") &&
                HostInfrastructureContracts.isWebViewPlugin(it.declaringClass)
        },
    )
    val rxSubscribe = DexKitMethodQuery(
        id = WelfareTargetKeys.rxSubscribe.id.value,
        purpose = "Rx two-consumer subscription preserving host callbacks",
        query = { matcher { declaredClass(Classes.RX_OBSERVABLE); name("subscribe"); paramCount(2) } },
        verify = WelfareRecoveryContracts::isSubscribe,
    )
    val responseBody = namedMethod(
        WelfareTargetKeys.responseBody.id.value, "Retrofit synchronous response body",
        "retrofit2", "body", paramCount = 0, returnType = Any::class.java, ownerName = Classes.RETROFIT_RESPONSE,
    )
    val responseSuccess = namedMethod(
        WelfareTargetKeys.responseSuccess.id.value, "server business success accessor",
        "com.qidian.QDReader.repository.entity", "isSuccess", paramCount = 0,
        returnType = Boolean::class.javaPrimitiveType, ownerName = Classes.SERVER_RESPONSE,
    )
    val responseData = namedMethod(
        WelfareTargetKeys.responseData.id.value, "server business data accessor",
        "com.qidian.QDReader.repository.entity", "getData", paramCount = 0,
        returnType = Any::class.java, ownerName = Classes.SERVER_RESPONSE,
    )
    val rewardRisk = namedMethod(
        WelfareTargetKeys.rewardRisk.id.value, "reward risk result accessor",
        "com.qidian.QDReader.repository.entity", "getRiskConf", paramCount = 0,
        returnTypeName = Classes.VERIFY_RESULT, ownerName = Classes.REWARD_RESULT_BEAN,
    )
    val rewardList = namedMethod(
        WelfareTargetKeys.rewardList.id.value, "reward list accessor",
        "com.qidian.QDReader.repository.entity", "getRewardList", paramCount = 0,
        returnType = List::class.java, ownerName = Classes.REWARD_RESULT_BEAN,
    )
    val userManagerInstance = namedMethod(
        WelfareTargetKeys.userManagerInstance.id.value, "current user manager singleton",
        "com.qidian.QDReader.component.user", "getInstance", paramCount = 0,
        returnTypeName = Classes.QD_USER_MANAGER, ownerName = Classes.QD_USER_MANAGER,
    )
    val browserWebView = namedMethod(
        WelfareTargetKeys.browserWebView.id.value, "browser fragment WebView accessor",
        "com.qidian.QDReader.ui.fragment", "getWebView", paramCount = 0,
        returnTypeName = Classes.TENCENT_WEBVIEW, ownerName = Classes.QD_BROWSER_FRAGMENT,
    )
    val pageStarted = namedMethod(
        WelfareTargetKeys.browserPageStarted.id.value, "browser document generation start",
        "com.qidian.QDReader.ui.fragment", "onPageStarted", paramTypes = listOf("java.lang.String"),
        returnType = Void.TYPE, ownerName = Classes.QD_BROWSER_FRAGMENT,
    )
    val webViewUrl = namedMethod(
        WelfareTargetKeys.webViewUrl.id.value, "WebView current URL",
        "com.tencent.smtt.sdk", "getUrl", paramCount = 0,
        returnType = String::class.java, ownerName = Classes.TENCENT_WEBVIEW,
    )
    val pageFinished = namedMethod(
        WelfareTargetKeys.browserPageFinished.id.value, "browser document ready for reward popup presentation",
        "com.qidian.QDReader.ui.fragment", "onPageFinished", paramTypes = listOf("java.lang.String"),
        returnType = Void.TYPE, ownerName = Classes.QD_BROWSER_FRAGMENT,
    )
    val webViewEvaluate = namedMethod(
        WelfareTargetKeys.webViewEvaluate.id.value, "WebView JavaScript evaluation with receipt",
        "com.tencent.smtt.sdk", "evaluateJavascript",
        paramTypes = listOf("java.lang.String", Classes.TENCENT_VALUE_CALLBACK),
        returnType = Void.TYPE, ownerName = Classes.TENCENT_WEBVIEW,
    )
}
