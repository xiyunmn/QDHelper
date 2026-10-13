package com.xiyunmn.qdhelper.hook.activity

import com.xiyunmn.qdhelper.foundation.identity.OwnerId

enum class ActivityEvent {
    CREATE,
    RESUME,
    PAUSE,
    DESTROY,
    TOUCH_OBSERVED,
    WINDOW_FOCUS_CHANGED,
    ACTIVITY_RESULT,
}

/** Immutable observation; subscribers never receive the host's mutable MotionEvent. */
data class ActivityTouchSample(
    val action: Int,
    val rawX: Float,
    val rawY: Float,
    val eventTimeMillis: Long,
)

data class ActivitySubscription(
    val owner: OwnerId,
    val id: String,
    val priority: Int,
    val events: Set<ActivityEvent>,
)

sealed interface ActivitySubscriptionResult {
    data class Registered(val registration: AutoCloseable) : ActivitySubscriptionResult
    data object DuplicateSubscriber : ActivitySubscriptionResult
}

interface ActivityCallbackHub {
    fun subscribe(
        subscription: ActivitySubscription,
        callback: (ActivityCallback) -> Unit,
    ): ActivitySubscriptionResult
}

sealed interface ActivityCallback {
    val activity: Any

    data class Created(override val activity: Any, val state: Any?) : ActivityCallback
    data class Resumed(override val activity: Any) : ActivityCallback
    data class Paused(override val activity: Any) : ActivityCallback
    data class Destroyed(override val activity: Any) : ActivityCallback
    data class TouchObserved(override val activity: Any, val sample: ActivityTouchSample) : ActivityCallback
    data class WindowFocusChanged(override val activity: Any, val hasFocus: Boolean) : ActivityCallback
    data class Result(
        override val activity: Any,
        val requestCode: Int,
        val resultCode: Int,
        val data: Any?,
    ) : ActivityCallback
}
