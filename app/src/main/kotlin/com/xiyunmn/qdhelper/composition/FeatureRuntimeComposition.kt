package com.xiyunmn.qdhelper.composition

import android.app.Activity
import android.content.Intent
import com.xiyunmn.qdhelper.capability.freecopy.FreeCopyDialog
import com.xiyunmn.qdhelper.feature.api.runtime.ActivityLifecycleCallbacks
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureResources
import com.xiyunmn.qdhelper.hook.runtime.HookRuntime
import com.xiyunmn.qdhelper.feature.api.runtime.ActivityLifecycleBridge
import com.xiyunmn.qdhelper.feature.api.runtime.ActivityResultBridge
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureRuntimeServices
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import com.xiyunmn.qdhelper.feature.api.runtime.NativeNetBridgeService
import com.xiyunmn.qdhelper.feature.contenttools.host.BookCoverGatewayFactory
import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import com.xiyunmn.qdhelper.hook.activity.ActivityCallback
import com.xiyunmn.qdhelper.hook.activity.ActivityEvent
import com.xiyunmn.qdhelper.hook.activity.ActivityResultRegistry
import com.xiyunmn.qdhelper.hook.activity.ActivitySubscription
import com.xiyunmn.qdhelper.hook.activity.ActivitySubscriptionResult
import com.xiyunmn.qdhelper.hook.activity.ActivityTouchSample
import com.xiyunmn.qdhelper.host.api.target.HostTargetResolver
import com.xiyunmn.qdhelper.host.api.reflection.HostReflection
import com.xiyunmn.qdhelper.host.api.gateway.NativeNetResponseTransformer
import com.xiyunmn.qdhelper.host.resolver.catalog.MethodTargetResolverAdapter
import com.xiyunmn.qdhelper.host.resolver.reflection.HostReflectionGatewayAdapter
import com.xiyunmn.qdhelper.runtime.ActivityCallbacks as RuntimeActivityCallbacks
import com.xiyunmn.qdhelper.runtime.HookModuleAdapter
import com.xiyunmn.qdhelper.runtime.NativeNetBridgeRuntime
import com.xiyunmn.qdhelper.runtime.ViewLongPressCallbacks

/** Wires feature-facing runtime contracts to the application-owned services. */
internal object FeatureRuntimeComposition {
    fun configure() {
        HostTargetResolver.configure(MethodTargetResolverAdapter())
        HostReflection.configure(HostReflectionGatewayAdapter)
        FeatureRuntimeServices.activityResults = object : ActivityResultBridge {
            override fun register(
                owner: OwnerId,
                requestCode: Int,
                handler: (Activity, Int, Intent?) -> Boolean,
            ): ActivityResultRegistry.RegistrationResult =
                RuntimeActivityCallbacks.registerResult(owner, requestCode, handler)
        }
        FeatureRuntimeServices.activityLifecycle = object : ActivityLifecycleBridge {
            override fun currentActivities(): List<Activity> = RuntimeActivityCallbacks.currentActivities()

            override fun subscribe(
                owner: OwnerId,
                id: String,
                priority: Int,
                created: ((Activity) -> Unit)?,
                resumed: ((Activity) -> Unit)?,
                windowFocusChanged: ((Activity, Boolean) -> Unit)?,
                paused: ((Activity) -> Unit)?,
                destroyed: ((Activity) -> Unit)?,
                touchObserved: ((Activity, ActivityTouchSample) -> Unit)?,
            ): AutoCloseable? {
                val events = buildSet {
                    if (created != null) add(ActivityEvent.CREATE)
                    if (resumed != null) add(ActivityEvent.RESUME)
                    if (windowFocusChanged != null) add(ActivityEvent.WINDOW_FOCUS_CHANGED)
                    if (paused != null) add(ActivityEvent.PAUSE)
                    if (destroyed != null) add(ActivityEvent.DESTROY)
                    if (touchObserved != null) add(ActivityEvent.TOUCH_OBSERVED)
                }
                if (events.isEmpty()) return null
                return when (
                    val result = RuntimeActivityCallbacks.subscribe(
                        ActivitySubscription(owner, id, priority, events),
                    ) { callback ->
                        when (callback) {
                            is ActivityCallback.Created -> (callback.activity as? Activity)?.let { created?.invoke(it) }
                            is ActivityCallback.Resumed -> (callback.activity as? Activity)?.let { resumed?.invoke(it) }
                            is ActivityCallback.Paused -> (callback.activity as? Activity)?.let { paused?.invoke(it) }
                            is ActivityCallback.Destroyed -> (callback.activity as? Activity)?.let { destroyed?.invoke(it) }
                            is ActivityCallback.TouchObserved -> (callback.activity as? Activity)
                                ?.let { touchObserved?.invoke(it, callback.sample) }
                            is ActivityCallback.WindowFocusChanged -> (callback.activity as? Activity)
                                ?.let { windowFocusChanged?.invoke(it, callback.hasFocus) }
                            is ActivityCallback.Result -> Unit
                        }
                    }
                ) {
                    is ActivitySubscriptionResult.Registered -> result.registration
                    ActivitySubscriptionResult.DuplicateSubscriber -> {
                        HookRuntime.w("Activity lifecycle subscriber duplicate: owner=$owner, id=$id")
                        null
                    }
                }
            }
        }
        FeatureRuntimeServices.longPressRouter = ViewLongPressCallbacks
        FreeCopyDialog.configure(
            registerSession = { session ->
                val id = "free-copy.dialog.${System.identityHashCode(session)}"
                val lifecycle = ActivityLifecycleCallbacks.subscribe(
                    OwnerId("free-copy"), id,
                    resumed = { if (it === session.activity) session.resume() },
                    paused = { if (it === session.activity) session.pause() },
                    destroyed = { if (it === session.activity) session.close() },
                )
                val cleanup = FeatureResources.register(id) { session.activity.runOnUiThread { session.close() } }
                AutoCloseable { lifecycle?.close(); cleanup.close() }
            },
            onError = { HookRuntime.w("Free copy dialog failed: ${it.javaClass.name}") },
        )
        FeatureRuntimeServices.nativeNet = object : NativeNetBridgeService {
            override fun register(
                ownerId: String,
                priority: Int,
                transformer: NativeNetResponseTransformer,
            ): AutoCloseable = NativeNetBridgeRuntime.register(ownerId, priority, transformer)

            override fun install(module: HookModule, classLoader: ClassLoader, phase: String): Int {
                val adapter = module as? HookModuleAdapter ?: return 0
                return NativeNetBridgeRuntime.install(adapter.delegate, classLoader, phase)
            }
        }
        FeatureRuntimeServices.bookCoverFactory = BookCoverGatewayFactory::create
    }
}
