package com.xiyunmn.qdhelper.hook.view

import com.xiyunmn.qdhelper.foundation.identity.OwnerId

sealed interface LongPressResult {
    data object Pass : LongPressResult
    data class Return(val value: Boolean) : LongPressResult
}

data class LongPressRegistration(
    val owner: OwnerId,
    val id: String,
    val priority: Int,
    val enableLongClick: Boolean = true,
)

sealed interface LongPressRegistrationResult {
    data class Registered(val registration: AutoCloseable) : LongPressRegistrationResult
    data object DuplicateOwner : LongPressRegistrationResult
}

interface ViewLongPressRouter {
    fun register(
        target: Any,
        registration: LongPressRegistration,
        handler: (target: Any, proceed: () -> Boolean) -> LongPressResult,
    ): LongPressRegistrationResult
}
