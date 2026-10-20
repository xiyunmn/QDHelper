package com.xiyunmn.qdhelper.feature.diagnostics.identity

import android.content.ContentResolver
import android.provider.Settings
import com.xiyunmn.qdhelper.feature.api.runtime.ActivityLifecycleCallbacks
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureResources
import com.xiyunmn.qdhelper.feature.api.runtime.InstallableFeature
import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols as QDReaderHost
import com.xiyunmn.qdhelper.host.api.target.DiagnosticsTargetKeys
import com.xiyunmn.qdhelper.host.api.target.HostTargetResolver
import com.xiyunmn.qdhelper.host.api.target.NetworkIdentityTargetKeys
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Read-only probe for host new-device / identity signals.
 *
 * Logs stable one-way fingerprints, not full secrets.
 * Does not alter return values unless explicitly configured later.
 */
object DeviceIdentityProbeFeature : InstallableFeature {
    override val name: String = "DeviceIdentityProbeFeature"

    private const val FIRST_VERBOSE = 30L
    private const val SAMPLE_EVERY = 40L

    private val counters = ConcurrentHashMap<String, AtomicLong>()
    private val resolvedIdentityMethods = ConcurrentHashMap<String, Method>()
    private val cleanupRegistered = AtomicBoolean(false)

    @Volatile
    private var activitySubscription: AutoCloseable? = null

    @Volatile
    private var activeClassLoader: ClassLoader? = null

    init {
        ensureCleanup()
    }

    override fun install(
        module: HookModule,
        classLoader: ClassLoader,
        processName: String,
        phase: String,
    ) {
        ensureCleanup()
        activeClassLoader = classLoader
        if (!SettingsRuntime.isDeviceIdentityProbeEnabled(HostSessionStore.contextOrNull())) {
            FeatureHooks.i("Device identity probe skipped: disabled, phase=$phase")
            return
        }
        if (!QDReaderHost.isMainProcess(processName)) {
            FeatureHooks.i("Device identity probe skip non-main process=$processName")
            return
        }

        var hooks = 0
        hooks += hookCommonLibDevice(module, classLoader, phase)
        hooks += DeviceIdentityEvidenceProbe.install(module, classLoader, phase)
        hooks += hookFakeIdentity(module, classLoader, phase)
        hooks += hookAppConfigFreshman(module, classLoader, phase)
        hooks += hookApplicationContextFirstLaunch(module, classLoader, phase)
        hooks += hookSettingsSecureAndroidId(module, phase)
        hooks += hookOaidUtil(module, classLoader, phase)
        hooks += hookOStarUpdate(module, classLoader, phase)
        hooks += hookFockReport(module, classLoader, phase)
        hooks += hookNetworkInterfaceInfo(module, classLoader, phase)
        hooks += hookMainTrackDeviceInfo(module, classLoader, phase)
        hooks += subscribeActivitySnapshot()

        FeatureHooks.i(
            "Device identity probe finished: phase=$phase, process=$processName, installedHooks=$hooks",
        )
        maybeLogHostSnapshot("install:$phase")
    }

    private fun ensureCleanup() {
        if (!cleanupRegistered.compareAndSet(false, true)) return
        FeatureResources.register("DeviceIdentityProbeFeature") {
            counters.clear()
            resolvedIdentityMethods.clear()
            activitySubscription?.close()
            activitySubscription = null
            activeClassLoader = null
            cleanupRegistered.set(false)
        }
    }

    private fun hookCommonLibDevice(module: HookModule, classLoader: ClassLoader, phase: String): Int {
        var n = 0
        val resolvedGetters = listOf(
            IdentityGetter(NetworkIdentityTargetKeys.commonDeviceQimei16, "q16_get"),
            IdentityGetter(NetworkIdentityTargetKeys.commonDeviceQimei36, "q36_get"),
            IdentityGetter(NetworkIdentityTargetKeys.cachedBeacon16, "beacon16"),
            IdentityGetter(NetworkIdentityTargetKeys.cachedBeacon36, "beacon36"),
            IdentityGetter(NetworkIdentityTargetKeys.cachedOstar16, "ostar16"),
            IdentityGetter(NetworkIdentityTargetKeys.cachedOstar36, "ostar36"),
            IdentityGetter(DiagnosticsTargetKeys.hostOaidGetter, "oaid_get"),
        )
        resolvedGetters.forEach { getter ->
            resolveIdentityMethod(
                classLoader,
                getter.key,
                QDReaderHost.Classes.COMMON_LIB_DEVICE,
                phase,
            )
                ?.let { method ->
                    n += installAfterString(module, method, "id.${getter.label}", phase)
                }
        }
        return n
    }

    private fun hookFakeIdentity(module: HookModule, classLoader: ClassLoader, phase: String): Int {
        var n = 0
        val resolvedGetters = listOf(
            IdentityGetter(NetworkIdentityTargetKeys.fakeLocal16, "fake_local16", requireStatic = false),
            IdentityGetter(NetworkIdentityTargetKeys.fakeLocal36, "fake_local36", requireStatic = false),
            IdentityGetter(NetworkIdentityTargetKeys.fakeOstar16, "fake_ostar16", requireStatic = false),
            IdentityGetter(NetworkIdentityTargetKeys.fakeOstar36, "fake_ostar36", requireStatic = false),
            IdentityGetter(NetworkIdentityTargetKeys.fake16, "fake16", requireStatic = false),
            IdentityGetter(NetworkIdentityTargetKeys.fake36, "fake36", requireStatic = false),
        )
        resolvedGetters.forEach { getter ->
            resolveIdentityMethod(
                classLoader,
                getter.key,
                QDReaderHost.Classes.COMMON_LIB_FAKE_IDENTITY,
                phase,
                getter.requireStatic,
            )?.let { method ->
                n += installAfterString(module, method, "id.${getter.label}", phase)
            }
        }
        return n
    }

    private fun hookAppConfigFreshman(module: HookModule, classLoader: ClassLoader, phase: String): Int {
        var n = 0
        val companion = FeatureHooks.findClassOrNull(QDReaderHost.Classes.QD_APP_CONFIG_HELPER_COMPANION, classLoader)
        if (companion != null) {
            listOf(
                "getFreshMan" to "freshman",
                "getFreshManDefaultTrue" to "freshman_default",
                "isNewUserFreeReading" to "limit_free",
                "getShowLimitFreePopup" to "limit_free_popup",
                "getIsNewUserTrained" to "new_user_trained",
            ).forEach { (methodName, label) ->
                FeatureHooks.declaredMethodsNamed(companion, methodName).firstOrNull {
                    it.parameterTypes.isEmpty()
                }?.let { method ->
                    n += installAfterAny(module, method, "cfg.$label", phase)
                }
            }
        }
        val helper = FeatureHooks.findClassOrNull(QDReaderHost.Classes.QD_APP_CONFIG_HELPER, classLoader)
        if (helper != null) {
            listOf("X", "U0", "isNewUserFreeReading").forEach { methodName ->
                FeatureHooks.declaredMethodsNamed(helper, methodName).filter { it.parameterTypes.isEmpty() }.forEach { method ->
                    n += installAfterAny(module, method, "cfg.helper.$methodName", phase)
                }
            }
        }
        // AppConfigBean getters if present after config load
        val bean = FeatureHooks.findClassOrNull(
            QDReaderHost.Classes.APP_CONFIG_BEAN,
            classLoader,
        )
        if (bean != null) {
            listOf("isFreshmanLimitFree", "isFreeReadingUser", "isReceiveFreeReading", "isNewDeviceForBubble", "getFreshman").forEach { methodName ->
                FeatureHooks.declaredMethodsNamed(bean, methodName).firstOrNull { it.parameterTypes.isEmpty() }?.let { method ->
                    n += installAfterAny(module, method, "cfg.bean.$methodName", phase)
                }
            }
        }
        val freshman = FeatureHooks.findClassOrNull(
            QDReaderHost.Classes.FRESHMAN_BEAN,
            classLoader,
        )
        if (freshman != null) {
            listOf("isFreshman", "isNewDevice", "getExpiredTips", "getShowLimitFreePopup", "getSource").forEach { methodName ->
                FeatureHooks.declaredMethodsNamed(freshman, methodName).firstOrNull { it.parameterTypes.isEmpty() }?.let { method ->
                    n += installAfterAny(module, method, "cfg.freshman.$methodName", phase)
                }
            }
        }
        return n
    }

    private fun hookApplicationContextFirstLaunch(module: HookModule, classLoader: ClassLoader, phase: String): Int {
        val clazz = FeatureHooks.findClassOrNull(QDReaderHost.Classes.APPLICATION_CONTEXT, classLoader) ?: return 0
        var n = 0
        FeatureHooks.declaredMethodsNamed(clazz, "isFirstLaunch").firstOrNull { it.parameterTypes.isEmpty() }?.let {
            n += installAfterAny(module, it, "life.isFirstLaunch", phase)
        }
        FeatureHooks.declaredMethodsNamed(clazz, "setIsFirstLaunch").firstOrNull {
            it.parameterTypes.size == 1 && (it.parameterTypes[0] == Boolean::class.javaPrimitiveType || it.parameterTypes[0] == java.lang.Boolean::class.java)
        }?.let { method ->
            n += installAroundArg(module, method, "life.setIsFirstLaunch", phase)
        }
        return n
    }

    private fun hookSettingsSecureAndroidId(module: HookModule, phase: String): Int {
        val method = FeatureHooks.findDeclaredMethodOrNull(
            Settings.Secure::class.java,
            "getString",
            ContentResolver::class.java,
            String::class.java,
        ) ?: return 0
        val key = "id.settings_secure_getString"
        val ok = FeatureHooks.hookAround(
            module = module,
            executable = method,
            feature = "probe.device.$key",
            id = "probe.device.$key",
        ) { chain ->
            val name = chain.args.getOrNull(1) as? String
            val result = chain.proceed()
            if (name == Settings.Secure.ANDROID_ID || name.equals("android_id", ignoreCase = true)) {
                logHit(key, "name=$name, value=${fp(result as? String)}")
            }
            result
        }
        return if (ok) 1 else 0
    }

    private fun hookOaidUtil(module: HookModule, classLoader: ClassLoader, phase: String): Int {
        val clazz = FeatureHooks.findClassOrNull(QDReaderHost.Classes.OAID_UTIL, classLoader) ?: return 0
        var n = 0
        // Kotlin object methods often appear as INSTANCE methods
        FeatureHooks.declaredMethodsNamed(clazz, "init").forEach { method ->
            n += installAroundArg(module, method, "oaid.util.init", phase)
        }
        FeatureHooks.declaredMethodsNamed(clazz, "checkOaidKey").forEach { method ->
            n += installAroundArg(module, method, "oaid.util.checkOaidKey", phase)
        }
        FeatureHooks.declaredMethodsNamed(clazz, "reGetOaid").forEach { method ->
            n += installAroundArg(module, method, "oaid.util.reGetOaid", phase)
        }
        // The callback is typed as the abstract IdSupplier interface, but the runtime payload is
        // the stable MIIT SDK implementation across the active audit interval. Hook the concrete getters.
        val supplier = FeatureHooks.findClassOrNull(QDReaderHost.Classes.OAID_SUPPLIER_IMPL, classLoader)
        if (supplier != null) {
            val getters = OaidProbeTargets.getterNames.flatMap { name ->
                FeatureHooks.declaredMethodsNamed(supplier, name)
            }
            OaidProbeTargets.concreteStringGetters(getters).forEach { method ->
                n += installAfterString(module, method, "oaid.supplier.${method.name}", phase)
            }
        }
        return n
    }

    private fun hookOStarUpdate(module: HookModule, classLoader: ClassLoader, phase: String): Int {
        val clazz = FeatureHooks.findClassOrNull(
            QDReaderHost.Classes.OSTAR_IMPL,
            classLoader,
        ) ?: return 0
        var n = 0
        FeatureHooks.declaredMethodsNamed(clazz, "update").forEach { method ->
            n += installAroundArg(module, method, "ostar.update", phase)
        }
        FeatureHooks.declaredMethodsNamed(clazz, "trigger").forEach { method ->
            n += installAroundArg(module, method, "ostar.trigger", phase)
        }
        return n
    }

    private fun hookFockReport(module: HookModule, classLoader: ClassLoader, phase: String): Int {
        val clazz = FeatureHooks.findClassOrNull(QDReaderHost.Classes.FOCK_UTIL, classLoader) ?: return 0
        var n = 0
        FeatureHooks.declaredMethodsNamed(clazz, "reportDeviceInfo").forEach { method ->
            n += installAroundArg(module, method, "fock.reportDeviceInfo", phase)
        }
        FeatureHooks.declaredMethodsNamed(clazz, "init").forEach { method ->
            n += installAroundArg(module, method, "fock.init", phase)
        }
        FeatureHooks.declaredMethodsNamed(clazz, "run").forEach { method ->
            n += installAroundArg(module, method, "fock.run", phase)
        }
        return n
    }

    private fun hookNetworkInterfaceInfo(module: HookModule, classLoader: ClassLoader, phase: String): Int {
        val clazz = FeatureHooks.findClassOrNull(
            QDReaderHost.Classes.QD_NET_UTIL,
            classLoader,
        ) ?: return 0
        var n = 0
        listOf(
            "getEncryptedNetworkInterfaceInfo",
            "getNetworkInterfaceInfo",
            "getNetworkInterfaceInfoFromCache",
            "initNetworkInterfaceInfoCache",
        ).forEach { name ->
            FeatureHooks.declaredMethodsNamed(clazz, name).forEach { method ->
                n += installAroundArg(module, method, "net.$name", phase)
            }
        }
        return n
    }

    private fun hookMainTrackDeviceInfo(module: HookModule, classLoader: ClassLoader, phase: String): Int {
        val clazz = FeatureHooks.findClassOrNull(QDReaderHost.Classes.MAIN_GROUP_ACTIVITY, classLoader) ?: return 0
        var n = 0
        FeatureHooks.declaredMethodsNamed(clazz, "trackDeviceInfo").forEach { method ->
            n += installAroundArg(module, method, "main.trackDeviceInfo", phase)
        }
        return n
    }

    private fun subscribeActivitySnapshot(): Int {
        activitySubscription?.let { return 1 }
        val subscription = ActivityLifecycleCallbacks.subscribe(
            owner = OwnerId("diagnostics"),
            id = "probe.device.life.activity_snapshot",
            resumed = { activity ->
            val name = activity.javaClass.name
            if (name.contains("SplashActivity") || name.contains("MainGroupActivity")) {
                maybeLogHostSnapshot("activity:${QDReaderHost.classLabel(name)}")
            }
            },
        ) ?: return 0
        activitySubscription = subscription
        return 1
    }

    private fun installAfterString(module: HookModule, method: Method, id: String, phase: String): Int {
        val key = "$id:${method.toGenericString()}"
        val ok = FeatureHooks.hookAfter(
            module = module,
            executable = method,
            feature = "probe.device.$id",
            id = "probe.device.$key",
        ) { _, result ->
            logHit(id, "ret=${fp(result as? String)}")
        }
        return if (ok) 1 else 0
    }

    private fun installAfterAny(module: HookModule, method: Method, id: String, phase: String): Int {
        val key = "$id:${method.toGenericString()}"
        val ok = FeatureHooks.hookAfter(
            module = module,
            executable = method,
            feature = "probe.device.$id",
            id = "probe.device.$key",
        ) { _, result ->
            val configContext = if (id.startsWith("cfg.")) {
                ", configContext=${DeviceIdentityEvidenceProbe.configContextTag()}"
            } else {
                ""
            }
            logHit(id, "ret=${summarize(result)}$configContext")
        }
        return if (ok) 1 else 0
    }

    private fun installAroundArg(module: HookModule, method: Method, id: String, phase: String): Int {
        val key = "$id:${method.toGenericString()}"
        val ok = FeatureHooks.hookAround(
            module = module,
            executable = method,
            feature = "probe.device.$id",
            id = "probe.device.$key",
        ) { chain ->
            val argsSummary = chain.args.mapIndexed { index, value ->
                "a$index=${summarize(value)}"
            }.joinToString(",")
            logHit(id, "begin args=[$argsSummary]")
            val result = chain.proceed()
            logHit(id, "end ret=${summarize(result)}")
            result
        }
        return if (ok) 1 else 0
    }

    private fun maybeLogHostSnapshot(reason: String) {
        if (!SettingsRuntime.isDeviceIdentityProbeEnabled(HostSessionStore.contextOrNull())) return
        // allow a few snapshots across splash/main
        val count = counters.computeIfAbsent("snapshot") { AtomicLong(0L) }.incrementAndGet()
        if (count > 6L) return
        val context = HostSessionStore.contextOrNull()
        val androidId = runCatching {
            context?.contentResolver?.let { Settings.Secure.getString(it, Settings.Secure.ANDROID_ID) }
        }.getOrNull()
        val classLoader = activeClassLoader
        val firstLaunch = runCatching {
            val clazz = classLoader?.let {
                FeatureHooks.findClassOrNull(QDReaderHost.Classes.APPLICATION_CONTEXT, it)
            } ?: return@runCatching null
            val method = FeatureHooks.findDeclaredMethodOrNull(clazz, "isFirstLaunch")
                ?: return@runCatching null
            FeatureHooks.invoke(method, null)
        }.getOrNull()
        val q16 = callResolvedStaticString(NetworkIdentityTargetKeys.commonDeviceQimei16)
        val q36 = callResolvedStaticString(NetworkIdentityTargetKeys.commonDeviceQimei36)
        val oaid = callResolvedStaticString(DiagnosticsTargetKeys.hostOaidGetter)
        val freshman = runCatching {
            val companion = classLoader?.let {
                FeatureHooks.findClassOrNull(QDReaderHost.Classes.QD_APP_CONFIG_HELPER_COMPANION, it)
            } ?: return@runCatching null
            val method = FeatureHooks.declaredMethodsNamed(companion, "getFreshMan")
                .singleOrNull { it.parameterTypes.isEmpty() }
                ?: return@runCatching null
            FeatureHooks.invoke(method, companionInstance(companion))
        }.getOrNull()
        val limitFree = runCatching {
            val companion = classLoader?.let {
                FeatureHooks.findClassOrNull(QDReaderHost.Classes.QD_APP_CONFIG_HELPER_COMPANION, it)
            } ?: return@runCatching null
            val method = FeatureHooks.declaredMethodsNamed(companion, "isNewUserFreeReading")
                .singleOrNull { it.parameterTypes.isEmpty() }
                ?: return@runCatching null
            FeatureHooks.invoke(method, companionInstance(companion))
        }.getOrNull()
        val startupReportBlock = SettingsRuntime.isStartupReportBlockEnabled(context)
        val trackBlock = SettingsRuntime.isTrackDeviceInfoBlockEnabled(context)
        val fockBlock = SettingsRuntime.isFockReportBlockEnabled(context)
        val experimentValid = !startupReportBlock && !trackBlock && !fockBlock

        FeatureHooks.i(
            "Device snapshot#$count reason=$reason, " +
                "aid_fp=${fp(androidId)}, firstLaunch=$firstLaunch, " +
                "q16_fp=${fp(q16)}, q36_fp=${fp(q36)}, oid_fp=${fp(oaid)}, " +
                "freshman=$freshman, limitFree=$limitFree, " +
                "moduleEnabled=${SettingsRuntime.isModuleEnabled(context)}, " +
                "startupReportBlock=$startupReportBlock, trackBlock=$trackBlock, fockBlock=$fockBlock, " +
                "experimentValid=$experimentValid, configContext=${DeviceIdentityEvidenceProbe.configContextTag()}",
        )
    }

    private fun callResolvedStaticString(
        key: com.xiyunmn.qdhelper.host.api.target.TargetKey.MethodTarget,
    ): String? {
        val classLoader = activeClassLoader ?: return null
        val method = resolvedIdentityMethods[key.id.value]
            ?: resolveIdentityMethod(
                classLoader,
                key,
                QDReaderHost.Classes.COMMON_LIB_DEVICE,
                "snapshot",
                true,
            )
            ?: return null
        return runCatching { FeatureHooks.invoke(method, null) as? String }.getOrNull()
    }

    private fun resolveIdentityMethod(
        classLoader: ClassLoader,
        key: com.xiyunmn.qdhelper.host.api.target.TargetKey.MethodTarget,
        ownerName: String,
        phase: String,
        requireStatic: Boolean = true,
    ): Method? {
        val method = HostTargetResolver.resolveMethod(
            context = HostSessionStore.contextOrNull(),
            classLoader = classLoader,
            processName = HostSessionStore.processName,
            key = key,
        )?.takeIf { candidate ->
            candidate.declaringClass.name == ownerName &&
                Modifier.isPublic(candidate.modifiers) &&
                Modifier.isStatic(candidate.modifiers) == requireStatic &&
                candidate.parameterTypes.isEmpty() &&
                candidate.returnType == String::class.java
        }
        if (method == null) {
            val expectedKind = if (requireStatic) "static" else "instance"
            FeatureHooks.w("Device probe: unresolved $expectedKind identity target ${key.id.value}, phase=$phase")
        } else {
            resolvedIdentityMethods[key.id.value] = method
        }
        return method
    }

    private data class IdentityGetter(
        val key: com.xiyunmn.qdhelper.host.api.target.TargetKey.MethodTarget,
        val label: String,
        val requireStatic: Boolean = true,
    )

    private fun companionInstance(companion: Class<*>): Any? = FeatureHooks.allDeclaredFields(companion)
        .singleOrNull { field -> Modifier.isStatic(field.modifiers) && field.type == companion }
        ?.let { field -> FeatureHooks.read(field, null) }

    internal fun logHit(id: String, detail: String) {
        if (!SettingsRuntime.isDeviceIdentityProbeEnabled()) return
        val count = counters.computeIfAbsent(id) { AtomicLong(0L) }.incrementAndGet()
        if (count <= FIRST_VERBOSE || count % SAMPLE_EVERY == 0L) {
            FeatureHooks.i("DeviceProbe $id#$count: $detail, thread=${Thread.currentThread().name}")
        }
    }

    private fun summarize(value: Any?): String {
        return when (value) {
            null -> "null"
            is String -> fp(value)
            is Boolean, is Number -> value.toString()
            is Array<*> -> "array(size=${value.size})"
            is Collection<*> -> "collection(size=${value.size})"
            else -> value.javaClass.simpleName
        }
    }

    /**
     * Stable one-way fingerprint for identity strings.
     */
    private fun fp(value: String?): String {
        return IdentityEvidenceCodec.fingerprint(value)
    }
}
