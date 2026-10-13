package com.xiyunmn.qdhelper.hook.activity

import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class ActivityResultRegistry {
    private val lock = ReentrantLock()
    private val handlers = linkedMapOf<Int, Registration>()

    fun register(
        owner: OwnerId,
        requestCode: Int,
        handler: (activity: Any, resultCode: Int, data: Any?) -> Boolean,
    ): RegistrationResult = lock.withLock {
        val existing = handlers[requestCode]
        if (existing != null) {
            return if (existing.owner == owner) {
                RegistrationResult.AlreadyRegistered(requestCode, owner)
            } else {
                RegistrationResult.RequestCodeConflict(requestCode, existing.owner)
            }
        }
        val registration = Registration(owner, handler)
        handlers[requestCode] = registration
        RegistrationResult.Registered(
            AutoCloseable { lock.withLock { handlers.remove(requestCode, registration) } },
        )
    }

    fun dispatch(activity: Any, requestCode: Int, resultCode: Int, data: Any?): Boolean {
        val handler = lock.withLock { handlers[requestCode]?.handler } ?: return false
        return handler(activity, resultCode, data)
    }

    fun clear() = lock.withLock { handlers.clear() }

    sealed interface RegistrationResult {
        data class Registered(val registration: AutoCloseable) : RegistrationResult
        data class AlreadyRegistered(val requestCode: Int, val owner: OwnerId) : RegistrationResult
        data class RequestCodeConflict(val requestCode: Int, val existingOwner: OwnerId) : RegistrationResult
    }

    private data class Registration(
        val owner: OwnerId,
        val handler: (activity: Any, resultCode: Int, data: Any?) -> Boolean,
    )
}
