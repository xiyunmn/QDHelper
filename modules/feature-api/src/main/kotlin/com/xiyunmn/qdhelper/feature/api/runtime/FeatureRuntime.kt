package com.xiyunmn.qdhelper.feature.api.runtime

import android.app.Activity
import android.content.Intent
import com.xiyunmn.qdhelper.foundation.logging.RuntimeLog
import com.xiyunmn.qdhelper.hook.view.LongPressRegistration
import com.xiyunmn.qdhelper.hook.view.LongPressRegistrationResult
import com.xiyunmn.qdhelper.hook.view.LongPressResult
import com.xiyunmn.qdhelper.hook.view.ViewLongPressRouter
import com.xiyunmn.qdhelper.hook.activity.ActivityResultRegistry
import com.xiyunmn.qdhelper.hook.activity.ActivityTouchSample
import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import com.xiyunmn.qdhelper.host.api.gateway.BookCoverGateway
import com.xiyunmn.qdhelper.host.api.gateway.NativeNetBridgeRegistry
import com.xiyunmn.qdhelper.host.api.gateway.NativeNetResponseTransformer
import com.xiyunmn.qdhelper.host.api.reflection.declaredFieldValue
import com.xiyunmn.qdhelper.host.api.reflection.declaredMethodOrNull
import com.xiyunmn.qdhelper.host.api.reflection.declaredMethodsNamed
import com.xiyunmn.qdhelper.host.api.reflection.findHostClass
import com.xiyunmn.qdhelper.host.api.reflection.hostDeclaredFields
import com.xiyunmn.qdhelper.host.api.reflection.hostDeclaredMethods
import com.xiyunmn.qdhelper.host.api.reflection.hostMethods
import com.xiyunmn.qdhelper.host.api.reflection.hostNestedClasses
import com.xiyunmn.qdhelper.host.api.reflection.fieldOrNull
import com.xiyunmn.qdhelper.host.api.reflection.constructorOrNull
import com.xiyunmn.qdhelper.host.api.reflection.HostAccess
import com.xiyunmn.qdhelper.host.api.reflection.HostReflection
import com.xiyunmn.qdhelper.host.api.reflection.callHost
import com.xiyunmn.qdhelper.host.api.reflection.callHostResult
import com.xiyunmn.qdhelper.host.api.reflection.callNoArgMethod
import com.xiyunmn.qdhelper.host.api.reflection.readHost
import com.xiyunmn.qdhelper.host.api.reflection.writeHost
import com.xiyunmn.qdhelper.host.api.reflection.newHostInstance
import com.xiyunmn.qdhelper.hook.resource.RuntimeCleanupRegistry
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.Field
import java.lang.reflect.Constructor
import java.lang.reflect.Modifier

interface HookChain {
    val thisObject: Any?
    val args: List<Any?>
    fun getArg(index: Int): Any? = args.getOrNull(index)
    fun proceed(arguments: Array<Any?>? = null): Any?
}

interface HookModule {
    fun hookAfter(
        executable: Executable?,
        feature: String,
        id: String?,
        callback: (HookChain, Any?) -> Unit,
    ): Boolean

    fun hookAround(
        executable: Executable?,
        feature: String,
        id: String?,
        callback: (HookChain) -> Any?,
    ): Boolean
}

interface InstallableFeature {
    val name: String
    fun install(module: HookModule, classLoader: ClassLoader, processName: String, phase: String)
}

object FeatureHooks {
    fun i(message: String) = RuntimeLog.i(message)
    fun w(message: String) = RuntimeLog.w(message)
    fun e(message: String, throwable: Throwable? = null) = RuntimeLog.e(message, throwable)

    fun findClassOrNull(name: String, classLoader: ClassLoader): Class<*>? =
        classLoader.findHostClass(name)

    fun findDeclaredMethodOrNull(clazz: Class<*>, name: String, vararg parameterTypes: Class<*>): Method? =
        clazz.declaredMethodOrNull(name, *parameterTypes)

    fun declaredMethodsNamed(clazz: Class<*>, name: String): List<Method> = clazz.declaredMethodsNamed(name)

    fun allDeclaredMethods(clazz: Class<*>): List<Method> = clazz.hostDeclaredMethods()

    fun methodsInHierarchy(clazz: Class<*>): List<Method> = clazz.hostMethods()

    /**
     * Returns public host methods through the configured gateway.  The small JVM fallback is
     * intentionally limited to tests/model-only callers where the application composition has
     * not installed a gateway yet; production host access always goes through HostReflection.
     */
    fun publicMethods(clazz: Class<*>): List<Method> {
        if (HostReflection.isConfigured()) {
            return methodsInHierarchy(clazz).filter { Modifier.isPublic(it.modifiers) }
        }
        return runCatching { clazz.methods.toList() }.getOrDefault(emptyList())
    }

    fun publicMethodOrNull(clazz: Class<*>, name: String, parameterCount: Int): Method? =
        publicMethods(clazz).firstOrNull { method ->
            method.name == name && method.parameterTypes.size == parameterCount
        }

    fun publicMethodsNamed(clazz: Class<*>, name: String, parameterCount: Int): List<Method> =
        publicMethods(clazz).filter { method ->
            method.name == name && method.parameterTypes.size == parameterCount
        }

    /** Invokes a no-argument host accessor without duplicating reflection plumbing in features. */
    fun invokeNoArgOrNull(target: Any?, name: String): Any? {
        target ?: return null
        if (HostReflection.isConfigured()) return target.callNoArgMethod(name)
        return runCatching {
            target.javaClass.methods.firstOrNull { method ->
                method.name == name && method.parameterTypes.isEmpty()
            }?.apply { isAccessible = true }?.invoke(target)
        }.getOrNull()
    }

    /** Invokes a resolved host method, with the same test-only fallback as [publicMethods]. */
    fun invokeOrNull(method: Method?, receiver: Any?, vararg args: Any?): Any? {
        method ?: return null
        if (HostReflection.isConfigured()) return invoke(method, receiver, *args)
        return runCatching { method.apply { isAccessible = true }.invoke(receiver, *args) }.getOrNull()
    }

    fun allDeclaredFields(clazz: Class<*>): List<Field> = clazz.hostDeclaredFields()

    fun nestedClasses(clazz: Class<*>): List<Class<*>> = clazz.hostNestedClasses()

    fun findFieldOrNull(clazz: Class<*>, name: String): Field? = clazz.fieldOrNull(name)

    fun findConstructorOrNull(clazz: Class<*>, vararg parameterTypes: Class<*>): Constructor<*>? =
        clazz.constructorOrNull(*parameterTypes)

    fun invokeResult(method: Method, receiver: Any?, vararg args: Any?): HostAccess<Any?> =
        method.callHostResult(receiver, *args)

    fun invoke(method: Method, receiver: Any?, vararg args: Any?): Any? = method.callHost(receiver, *args)

    fun read(field: Field, receiver: Any?): Any? = field.readHost(receiver)

    fun write(field: Field, receiver: Any?, value: Any?): Boolean = field.writeHost(receiver, value)

    fun newInstance(constructor: Constructor<*>, vararg args: Any?): Any? = constructor.newHostInstance(*args)

    fun readDeclaredFieldValue(instance: Any, name: String): Any? = instance.declaredFieldValue(name)

    fun hookAfter(
        module: HookModule,
        executable: Executable?,
        feature: String,
        id: String? = feature,
        after: (HookChain, Any?) -> Unit,
    ): Boolean = module.hookAfter(executable, feature, id, after)

    fun hookAround(
        module: HookModule,
        executable: Executable?,
        feature: String,
        id: String? = feature,
        around: (HookChain) -> Any?,
    ): Boolean = module.hookAround(executable, feature, id, around)
}

object FeatureResources {
    fun register(key: String, action: () -> Unit): AutoCloseable = RuntimeCleanupRegistry.register(key, action)
}

interface ActivityResultBridge {
    fun register(
        owner: OwnerId,
        requestCode: Int,
        handler: (Activity, Int, Intent?) -> Boolean,
    ): ActivityResultRegistry.RegistrationResult
}

/**
 * Feature-facing facade for the single Activity lifecycle bridge owned by the
 * application/runtime module.  Feature modules may subscribe to lifecycle
 * events without installing their own framework hooks.
 */
interface ActivityLifecycleBridge {
    fun currentActivities(): List<Activity> = emptyList()

    fun subscribe(
        owner: OwnerId,
        id: String,
        priority: Int = 0,
        created: ((Activity) -> Unit)? = null,
        resumed: ((Activity) -> Unit)? = null,
        windowFocusChanged: ((Activity, Boolean) -> Unit)? = null,
        paused: ((Activity) -> Unit)? = null,
        destroyed: ((Activity) -> Unit)? = null,
        touchObserved: ((Activity, ActivityTouchSample) -> Unit)? = null,
    ): AutoCloseable?
}

interface NativeNetBridgeService : NativeNetBridgeRegistry {
    fun install(module: HookModule, classLoader: ClassLoader, phase: String): Int
}

object FeatureRuntimeServices {
    @Volatile
    var activityResults: ActivityResultBridge? = null

    @Volatile
    var activityLifecycle: ActivityLifecycleBridge? = null

    @Volatile
    var longPressRouter: ViewLongPressRouter? = null

    @Volatile
    var nativeNet: NativeNetBridgeService? = null

    @Volatile
    var bookCoverFactory: ((ClassLoader) -> BookCoverGateway)? = null
}

object ActivityCallbacks {
    fun registerResult(
        owner: OwnerId,
        requestCode: Int,
        handler: (Activity, Int, Intent?) -> Boolean,
    ): ActivityResultRegistry.RegistrationResult = FeatureRuntimeServices.activityResults
        ?.register(owner, requestCode, handler)
        ?: ActivityResultRegistry.RegistrationResult.RequestCodeConflict(requestCode, OwnerId("runtime-unavailable"))
}

object ActivityLifecycleCallbacks {
    fun currentActivities(): List<Activity> = FeatureRuntimeServices.activityLifecycle?.currentActivities().orEmpty()

    fun subscribe(
        owner: OwnerId,
        id: String,
        priority: Int = 0,
        created: ((Activity) -> Unit)? = null,
        resumed: ((Activity) -> Unit)? = null,
        windowFocusChanged: ((Activity, Boolean) -> Unit)? = null,
        paused: ((Activity) -> Unit)? = null,
        destroyed: ((Activity) -> Unit)? = null,
        touchObserved: ((Activity, ActivityTouchSample) -> Unit)? = null,
    ): AutoCloseable? = FeatureRuntimeServices.activityLifecycle?.subscribe(
        owner = owner,
        id = id,
        priority = priority,
        created = created,
        resumed = resumed,
        windowFocusChanged = windowFocusChanged,
        paused = paused,
        destroyed = destroyed,
        touchObserved = touchObserved,
    )
}

object ViewLongPressCallbacks : ViewLongPressRouter {
    override fun register(
        target: Any,
        registration: LongPressRegistration,
        handler: (target: Any, proceed: () -> Boolean) -> LongPressResult,
    ): LongPressRegistrationResult = FeatureRuntimeServices.longPressRouter
        ?.register(target, registration, handler)
        ?: LongPressRegistrationResult.DuplicateOwner
}

object NativeNetBridgeRuntime : NativeNetBridgeService {
    override fun register(
        ownerId: String,
        priority: Int,
        transformer: NativeNetResponseTransformer,
    ): AutoCloseable = FeatureRuntimeServices.nativeNet?.register(ownerId, priority, transformer) ?: AutoCloseable {}

    override fun install(module: HookModule, classLoader: ClassLoader, phase: String): Int =
        FeatureRuntimeServices.nativeNet?.install(module, classLoader, phase) ?: 0
}

object BookCoverGateways {
    fun forClassLoader(classLoader: ClassLoader): BookCoverGateway =
        FeatureRuntimeServices.bookCoverFactory?.invoke(classLoader) ?: UnavailableBookCoverGateway

    private object UnavailableBookCoverGateway : BookCoverGateway {
        override fun loadCover(bookId: String) = null
    }
}
