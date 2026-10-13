package com.xiyunmn.qdhelper.host.api.target

/** High-risk account targets used by automatic account actions. */
object AccountTargetKeys {
    val checkInManagerInstance = method("account.auto_sign_in.manager.instance")
    val checkInRequest = method("account.auto_sign_in.manager.check_in_request")
    val alreadyCheckedIn = method("account.auto_sign_in.manager.already_checked_in")
    val syncCheckedIn = method("account.auto_sign_in.manager.sync_checked_in")
    val autoInvestDetailApi = method("account.auto_invest.detail_api")
    val autoInvestLegacyRxAdapter = method("account.auto_invest.legacy_rx_adapter")
    val autoInvestRequestApi = method("account.auto_invest.request_api")
    val autoInvestReaderBookId = method("account.auto_invest.reader_book_id")
    val userManagerIsLoggedIn = method("account.user_manager.is_logged_in")
    val userManagerUserId = method("account.user_manager.user_id")

    val autoSignIn: Set<TargetKey.MethodTarget> = linkedSetOf(
        checkInManagerInstance,
        checkInRequest,
        alreadyCheckedIn,
        syncCheckedIn,
    )

    val autoInvest: Set<TargetKey.MethodTarget> = linkedSetOf(
        autoInvestDetailApi,
        autoInvestLegacyRxAdapter,
        autoInvestRequestApi,
        autoInvestReaderBookId,
    )

    val userSession: Set<TargetKey.MethodTarget> = linkedSetOf(
        userManagerIsLoggedIn,
        userManagerUserId,
    )

    val all: Set<TargetKey.MethodTarget> = linkedSetOf<TargetKey.MethodTarget>().apply {
        addAll(autoSignIn)
        addAll(autoInvest)
        addAll(userSession)
    }

    private fun method(id: String) = TargetKey.MethodTarget(TargetId(id), Cardinality.One)
}
