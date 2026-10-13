package com.xiyunmn.qdhelper.host.api.target

/** High-risk welfare hook targets, split by host process. */
object WelfareTargetKeys {
    val flutterMethodCall = one("welfare.flutter.method_call")
    val flutterShowGdtAd = many("welfare.flutter.show_gdt_ad", "Verified Flutter GDT entry points across host variants.")
    val gdtRewardObservable = many("welfare.gdt_for_qd.reward_observable", "Verified GDT reward-observable entry points.")
    val tangramRewardShowAd = many("welfare.tangram.reward.show_ad", "Verified Tangram reward-ad entry points.")
    val h5RewardConfigMid = many("welfare.h5.reward_bridge.f1_config_mid", "Verified H5 reward bridges with config in the middle.")
    val h5RewardConfigLast = many("welfare.h5.reward_bridge.f1_config_last", "Verified H5 reward bridges with config last.")
    val h5RewardGame = many("welfare.h5.reward_bridge.r1_game", "Verified H5 game reward bridges.")
    val taskReadingPageApi = many("welfare.api.get_welfare_task_reading_page_v2", "All verified implementations of the welfare task API path.")
    val finishWatchApi = many("welfare.api.finish_watch", "All verified implementations of the finishWatch API path.")
    val finishWatchBusiness = many("welfare.extra.finish_watch_business", "Verified finish-watch business handlers across host variants.")
    val qqMiniLoadAd = many("welfare.qqmini.reward.load_ad", "Verified QQ mini-app reward load entry points.")
    val qqMiniShowAd = many("welfare.qqmini.reward.show_ad", "Verified QQ mini-app reward show entry points.")

    val nativeClaimBridge = one("welfare.recovery.native_bridge")
    val groupedClaimBridge = one("welfare.recovery.grouped_bridge")
    val nativeClaimEvent = one("welfare.recovery.native_event")
    val groupedClaimEvent = one("welfare.recovery.grouped_event")
    val groupedClaimError = one("welfare.recovery.grouped_error")
    val h5Callback = one("welfare.recovery.h5_callback")
    val rxSubscribe = one("welfare.recovery.rx_subscribe")
    val responseBody = one("welfare.recovery.response_body")
    val responseSuccess = one("welfare.recovery.response_success")
    val responseData = one("welfare.recovery.response_data")
    val rewardRisk = one("welfare.recovery.reward_risk")
    val rewardList = one("welfare.recovery.reward_list")
    val userManagerInstance = one("welfare.recovery.user_manager_instance")
    val browserWebView = one("welfare.recovery.browser_webview")
    val browserPageStarted = one("welfare.recovery.page_started")
    val browserPageFinished = one("welfare.popup.page_finished")
    val webViewUrl = one("welfare.recovery.webview_url")
    val webViewEvaluate = one("welfare.recovery.webview_evaluate")

    /** Each recovery member must resolve uniquely before the request adapter is installed. */
    val recovery: Set<TargetKey.MethodTarget> = linkedSetOf(
        nativeClaimBridge, groupedClaimBridge, nativeClaimEvent, groupedClaimEvent,
        groupedClaimError, h5Callback, rxSubscribe, responseBody, responseSuccess, responseData,
        rewardRisk, rewardList, userManagerInstance, browserWebView, browserPageStarted,
        webViewUrl, webViewEvaluate,
    )

    val main: Set<TargetKey.MethodTarget> = linkedSetOf(
        flutterMethodCall,
        flutterShowGdtAd,
        gdtRewardObservable,
        tangramRewardShowAd,
        h5RewardConfigMid,
        h5RewardConfigLast,
        h5RewardGame,
        taskReadingPageApi,
        finishWatchApi,
        finishWatchBusiness,
    ) + recovery + browserPageFinished

    val mini: Set<TargetKey.MethodTarget> = linkedSetOf(qqMiniLoadAd, qqMiniShowAd)
    val all: Set<TargetKey.MethodTarget> = main + mini

    private fun one(id: String) = TargetKey.MethodTarget(TargetId(id), Cardinality.One)

    private fun many(id: String, justification: String) = TargetKey.MethodTarget(
        TargetId(id),
        Cardinality.Many(min = 1, max = 256, justification = justification),
    )
}
