package com.xiyunmn.qdhelper.feature.navigation

import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols

/** The fourth argument carries the newer host's additional unread-dot condition. */
internal fun isAccountTagTextSignature(parameters: List<String>): Boolean {
    val legacy = listOf(HostSymbols.Classes.MAIN_GROUP_ACTIVITY, "int", "int")
    return parameters == legacy || parameters == legacy + "boolean"
}
