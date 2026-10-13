package com.xiyunmn.qdhelper.runtime

import android.app.Activity
import android.content.Intent
import android.content.ContextWrapper
import android.os.Bundle
import android.view.View
import android.view.MotionEvent
import android.view.inspector.WindowInspector
import com.xiyunmn.qdhelper.hook.resource.RuntimeCleanupRegistry
import com.xiyunmn.qdhelper.hook.runtime.HookRuntime
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import com.xiyunmn.qdhelper.hook.activity.ActivityCallback
import com.xiyunmn.qdhelper.hook.activity.ActivityCallbackHub
import com.xiyunmn.qdhelper.hook.activity.ActivityEvent
import com.xiyunmn.qdhelper.hook.activity.ActivityResultRegistry
import com.xiyunmn.qdhelper.hook.activity.ActivitySubscription
import com.xiyunmn.qdhelper.hook.activity.ActivitySubscriptionResult
import com.xiyunmn.qdhelper.hook.activity.ActivityTouchSample
import com.xiyunmn.qdhelper.hook.view.LongPressRegistration
import com.xiyunmn.qdhelper.hook.view.LongPressRegistrationResult
import com.xiyunmn.qdhelper.hook.view.LongPressHandlerRegistry
import com.xiyunmn.qdhelper.hook.view.LongPressResult
import com.xiyunmn.qdhelper.hook.view.ViewLongPressRouter
import io.github.libxposed.api.XposedModule
import java.util.Collections
import java.util.WeakHashMap
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

object RuntimeCallbackBridges {
    private val cleanupRegistered = AtomicBoolean(false)

    fun install(module: XposedModule) {
        ActivityCallbacks.install(module)
        ViewLongPressCallbacks.install(module)
        if (cleanupRegistered.compareAndSet(false, true)) {
            RuntimeCleanupRegistry.register("runtime-callback-bridges") {
                ActivityCallbacks.reset()
                ViewLongPressCallbacks.reset()
                cleanupRegistered.set(false)
            }
        }
    }
}

object ActivityCallbacks : ActivityCallbackHub {
    private val subscribers = CopyOnWriteArrayList<Subscriber>()
    private val resultRegistry = ActivityResultRegistry()

    private val observedActivities = Collections.synchronizedMap(WeakHashMap<Activity, Boolean>())

    /** Android 9 uses lifecycle observations; later releases can also discover existing windows. */
    fun currentActivities(): List<Activity> {
        val observed = synchronized(observedActivities) { observedActivities.keys.toList() }
        val windows = if (android.os.Build.VERSION.SDK_INT >= 29) {
            WindowInspector.getGlobalWindowViews().mapNotNull { root ->
                var context = root.findViewById<View>(android.R.id.content)?.context ?: root.context
                while (context is ContextWrapper && context !is Activity) {
                    val next = context.baseContext
                    if (next === context) break
                    context = next
                }
                context as? Activity
            }
        } else emptyList()
        return (observed + windows).distinct().filterNot { it.isDestroyed || it.isFinishing }
    }

    override fun subscribe(
        subscription: ActivitySubscription,
        callback: (ActivityCallback) -> Unit,
    ): ActivitySubscriptionResult {
        if (subscribers.any { it.subscription.owner == subscription.owner && it.subscription.id == subscription.id }) {
            return ActivitySubscriptionResult.DuplicateSubscriber
        }
        val subscriber = Subscriber(subscription, callback)
        subscribers += subscriber
        subscribers.sortByDescending { it.subscription.priority }
        return ActivitySubscriptionResult.Registered(AutoCloseable { subscribers.remove(subscriber) })
    }

    fun registerResult(
        owner: OwnerId,
        requestCode: Int,
        handler: (Activity, Int, Intent?) -> Boolean,
    ): ActivityResultRegistry.RegistrationResult {
        return resultRegistry.register(owner, requestCode) { activity, resultCode, data ->
            handler(activity as Activity, resultCode, data as? Intent)
        }
    }

    fun install(module: XposedModule) {
        hookAfter(module, "onCreate", arrayOf(Bundle::class.java)) { chain ->
            ActivityCallback.Created(chain.thisObject as Activity, chain.args.getOrNull(0))
        }
        hookAfter(module, "onResume", emptyArray()) { chain ->
            ActivityCallback.Resumed(chain.thisObject as Activity)
        }
        hookAfter(module, "onPause", emptyArray()) { chain ->
            ActivityCallback.Paused(chain.thisObject as Activity)
        }
        hookAfter(module, "onDestroy", emptyArray()) { chain ->
            ActivityCallback.Destroyed(chain.thisObject as Activity)
        }
        hookAfter(module, "dispatchTouchEvent", arrayOf(MotionEvent::class.java)) { chain ->
            val event = chain.args[0] as MotionEvent
            ActivityCallback.TouchObserved(
                chain.thisObject as Activity,
                ActivityTouchSample(event.actionMasked, event.rawX, event.rawY, event.eventTime),
            )
        }
        hookAfter(module, "onWindowFocusChanged", arrayOf(Boolean::class.javaPrimitiveType!!)) { chain ->
            ActivityCallback.WindowFocusChanged(
                chain.thisObject as Activity,
                chain.args.getOrNull(0) as? Boolean ?: false,
            )
        }
        hookAfter(
            module,
            "onActivityResult",
            arrayOf(Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Intent::class.java),
        ) { chain ->
            val activity = chain.thisObject as Activity
            val requestCode = chain.args.getOrNull(0) as Int
            val resultCode = chain.args.getOrNull(1) as Int
            val data = chain.args.getOrNull(2) as? Intent
            resultRegistry.dispatch(activity, requestCode, resultCode, data)
            ActivityCallback.Result(activity, requestCode, resultCode, data)
        }
    }

    internal fun reset() {
        subscribers.clear()
        resultRegistry.clear()
        observedActivities.clear()
    }

    private fun hookAfter(
        module: XposedModule,
        name: String,
        parameterTypes: Array<Class<*>>,
        callback: (io.github.libxposed.api.XposedInterface.Chain) -> ActivityCallback,
    ) {
        val method = runCatching {
            Activity::class.java.getDeclaredMethod(name, *parameterTypes).apply { isAccessible = true }
        }.getOrElse { throwable ->
            HookRuntime.w("Activity callback bridge missing: $name, error=${throwable.javaClass.name}")
            return
        }
        HookRuntime.hookAfter(
            module,
            method,
            "ActivityCallbacks.Activity.$name",
            "qdhelper.runtime.activity.$name",
        ) { chain, _ ->
            if (name != "dispatchTouchEvent" || subscribers.any { ActivityEvent.TOUCH_OBSERVED in it.subscription.events }) {
                dispatch(callback(chain))
            }
        }
    }

    private fun dispatch(callback: ActivityCallback) {
        (callback.activity as? Activity)?.let { activity ->
            if (callback is ActivityCallback.Destroyed) observedActivities.remove(activity)
            else observedActivities[activity] = true
        }
        val event = when (callback) {
            is ActivityCallback.Created -> ActivityEvent.CREATE
            is ActivityCallback.Resumed -> ActivityEvent.RESUME
            is ActivityCallback.Paused -> ActivityEvent.PAUSE
            is ActivityCallback.Destroyed -> ActivityEvent.DESTROY
            is ActivityCallback.TouchObserved -> ActivityEvent.TOUCH_OBSERVED
            is ActivityCallback.WindowFocusChanged -> ActivityEvent.WINDOW_FOCUS_CHANGED
            is ActivityCallback.Result -> ActivityEvent.ACTIVITY_RESULT
        }
        subscribers.forEach { subscriber ->
            if (event !in subscriber.subscription.events) return@forEach
            runCatching { subscriber.callback(callback) }.onFailure { throwable ->
                HookRuntime.w(
                    "Activity callback failed: owner=${subscriber.subscription.owner.value}, " +
                        "id=${subscriber.subscription.id}, error=${throwable.javaClass.name}",
                )
            }
        }
        HostSymbols.trackedActivityLabel(callback.activity.javaClass.name)?.let { label ->
            when (callback) {
                is ActivityCallback.Created -> HookRuntime.i("Activity.onCreate: $label")
                is ActivityCallback.Resumed -> HookRuntime.i("Activity.onResume: $label")
                is ActivityCallback.WindowFocusChanged, is ActivityCallback.Result,
                is ActivityCallback.Paused, is ActivityCallback.Destroyed, is ActivityCallback.TouchObserved -> Unit
            }
        }
    }

    private data class Subscriber(
        val subscription: ActivitySubscription,
        val callback: (ActivityCallback) -> Unit,
    )
}

object ViewLongPressCallbacks : ViewLongPressRouter {
    private val originalStates = Collections.synchronizedMap(WeakHashMap<View, Boolean>())
    private val handlers = LongPressHandlerRegistry { registration, throwable ->
        HookRuntime.w(
            "Long press callback failed: owner=${registration.owner.value}, " +
                "id=${registration.id}, error=${throwable.javaClass.name}",
        )
    }

    override fun register(
        target: Any,
        registration: LongPressRegistration,
        handler: (target: Any, proceed: () -> Boolean) -> LongPressResult,
    ): LongPressRegistrationResult {
        val view = target as? View ?: return LongPressRegistrationResult.DuplicateOwner
        val wasEmpty = !handlers.hasRegistrations(view)
        if (wasEmpty) originalStates[view] = view.isLongClickable
        val result = handlers.register(view, registration, handler)
        if (result !is LongPressRegistrationResult.Registered) {
            if (wasEmpty) originalStates.remove(view)
            return result
        }
        if (registration.enableLongClick && !view.isLongClickable) view.isLongClickable = true
        val targetRef = WeakReference(view)
        return LongPressRegistrationResult.Registered(AutoCloseable {
            result.registration.close()
            targetRef.get()?.let(::restoreIfUnused)
        })
    }

    fun install(module: XposedModule) {
        View::class.java.declaredMethods
            .filter { it.name == "performLongClick" && it.returnType == Boolean::class.javaPrimitiveType }
            .forEachIndexed { index, method ->
                HookRuntime.hookAround(
                    module,
                    method.apply { isAccessible = true },
                    "ViewLongPressCallbacks.View.performLongClick/${method.parameterCount}",
                    "qdhelper.runtime.view.perform_long_click.$index",
                ) { chain ->
                    val view = chain.thisObject as? View ?: return@hookAround chain.proceed()
                    when (val result = handlers.dispatch(view) { chain.proceed() as? Boolean ?: false }) {
                        LongPressResult.Pass -> chain.proceed()
                        is LongPressResult.Return -> result.value
                    }
                }
            }
    }

    internal fun reset() {
        val snapshot = synchronized(originalStates) {
            originalStates.toList().also { originalStates.clear() }
        }
        handlers.clear()
        snapshot.forEach { (view, original) -> view.isLongClickable = original }
    }

    private fun restoreIfUnused(view: View) {
        if (handlers.hasRegistrations(view)) return
        originalStates.remove(view)?.let { original -> view.isLongClickable = original }
    }
}
