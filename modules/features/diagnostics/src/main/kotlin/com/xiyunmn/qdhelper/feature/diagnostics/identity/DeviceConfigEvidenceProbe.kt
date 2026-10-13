package com.xiyunmn.qdhelper.feature.diagnostics.identity

import android.os.SystemClock
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureResources
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.hook.concurrency.ManagedExecutor
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import com.xiyunmn.qdhelper.host.api.target.DiagnosticsTargetKeys
import com.xiyunmn.qdhelper.host.api.target.HostTargetResolver
import com.xiyunmn.qdhelper.host.api.target.TargetKey
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.ArrayDeque
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** P0 evidence chain from getconf dispatch through runtime application and file persistence. */
internal object DeviceConfigEvidenceProbe {
    private const val APP_CONFIG_FILE = "app_config.json"
    private const val GETCONF_PATH = "argus/api/v1/client/getconf"

    private val traceSequence = AtomicLong()
    private val cleanupRegistered = AtomicBoolean(false)
    private val loaderTraces = Collections.synchronizedMap(WeakHashMap<Any, String>())
    private val pendingLoaderTraces = Collections.synchronizedMap(WeakHashMap<Any, ArrayDeque<String>>())
    private val configFileFields = ConcurrentHashMap<Class<*>, Field>()
    private val getconfServiceMethods = ConcurrentHashMap<Method, Boolean>()
    private val unresolvedLoaderShapes = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val configContext = ThreadLocal<String?>()
    private val fileWorker = ManagedExecutor(
        ownerKey = "feature.diagnostics.device-config-evidence",
        threadName = "QDHelper-ConfigEvidence",
        onFailure = { throwable -> FeatureHooks.e("Device config evidence worker failed", throwable) },
    )

    @Volatile
    private var latestConfigContext = "none"

    fun install(module: HookModule, classLoader: ClassLoader, phase: String): Int {
        ensureCleanup()
        var installed = 0
        installed += hookConfigRequest(module, classLoader, phase)
        installed += hookConfigApply(module, classLoader, phase)
        installed += hookGetconfApi(module, classLoader, phase)
        installed += hookAppConfigStage(
            module = module,
            classLoader = classLoader,
            phase = phase,
            key = DiagnosticsTargetKeys.appConfigCacheLoaded,
            stage = "cache",
        )
        installed += hookAppConfigStage(
            module = module,
            classLoader = classLoader,
            phase = phase,
            key = DiagnosticsTargetKeys.appConfigNetworkUpdated,
            stage = "network",
        )
        val cacheTrace = nextTrace()
        latestConfigContext = "cache_snapshot:$cacheTrace"
        scheduleConfigFileSummary(cacheTrace, "install_snapshot")
        return installed
    }

    fun configContextTag(): String = configContext.get() ?: latestConfigContext

    private fun ensureCleanup() {
        if (!cleanupRegistered.compareAndSet(false, true)) return
        FeatureResources.register("DeviceConfigEvidenceProbe") {
            loaderTraces.clear()
            pendingLoaderTraces.clear()
            configFileFields.clear()
            getconfServiceMethods.clear()
            unresolvedLoaderShapes.clear()
            configContext.remove()
            latestConfigContext = "none"
            fileWorker.close()
            cleanupRegistered.set(false)
        }
    }

    private fun hookConfigRequest(module: HookModule, classLoader: ClassLoader, phase: String): Int {
        val clazz = FeatureHooks.findClassOrNull(HostSymbols.Classes.CONFIG_LOADER, classLoader) ?: return 0
        val method = resolveOne(classLoader, DiagnosticsTargetKeys.configLoaderRequest) ?: run {
            FeatureHooks.w("Device config evidence: request entry unresolved, phase=$phase")
            return 0
        }
        val installed = FeatureHooks.hookAround(
            module = module,
            executable = method,
            feature = "probe.device.getconf_request",
            id = "probe.device.getconf_request:${method.toGenericString()}",
        ) { chain ->
            val loader = chain.thisObject
            if (!isAppConfigLoader(loader)) return@hookAround chain.proceed()
            val trace = nextTrace()
            if (loader != null) enqueueLoaderTrace(loader, trace)
            withConfigContext("request:$trace") {
                latestConfigContext = "request:$trace"
                DeviceIdentityProbeFeature.logHit(
                    "evidence.getconf",
                    "trace=$trace,request_begin,file=${safeConfigFileName(loader)}," +
                        "elapsed=${SystemClock.elapsedRealtime()}",
                )
                try {
                    chain.proceed().also { result ->
                        DeviceIdentityProbeFeature.logHit(
                            "evidence.getconf",
                            "trace=$trace,request_dispatched,result=${typeLabel(result)}," +
                                "elapsed=${SystemClock.elapsedRealtime()}",
                        )
                    }
                } catch (throwable: Throwable) {
                    DeviceIdentityProbeFeature.logHit(
                        "evidence.getconf",
                        "trace=$trace,request_error=${throwable.javaClass.simpleName}," +
                            "elapsed=${SystemClock.elapsedRealtime()}",
                    )
                    throw throwable
                }
            }
        }
        return if (installed) 1 else 0
    }

    private fun hookConfigApply(module: HookModule, classLoader: ClassLoader, phase: String): Int {
        val clazz = FeatureHooks.findClassOrNull(HostSymbols.Classes.CONFIG_LOADER, classLoader) ?: return 0
        val method = resolveOne(classLoader, DiagnosticsTargetKeys.configLoaderApply) ?: run {
            FeatureHooks.w("Device config evidence: response apply unresolved, phase=$phase")
            return 0
        }
        val installed = FeatureHooks.hookAround(
            module = module,
            executable = method,
            feature = "probe.device.getconf_apply",
            id = "probe.device.getconf_apply:${method.toGenericString()}",
        ) { chain ->
            val loader = chain.thisObject
            if (!isAppConfigLoader(loader)) return@hookAround chain.proceed()
            val trace = takeLoaderTrace(loader)
            val rawJson = chain.args.firstOrNull() as? String
            // Summarizing the payload walks the whole config JSON, so it runs on the probe worker
            // rather than on the host's config-apply thread. Ordering against `response_applied`
            // is not guaranteed; the shared `trace` is what stitches the chain back together.
            scheduleDecisionSummary(trace, "response_apply", rawJson)
            withConfigContext("network:$trace") {
                latestConfigContext = "network:$trace"
                try {
                    chain.proceed().also {
                        latestConfigContext = "network:$trace"
                        DeviceIdentityProbeFeature.logHit(
                            "evidence.getconf",
                            "trace=$trace,response_applied,elapsed=${SystemClock.elapsedRealtime()}",
                        )
                        scheduleConfigFileSummary(trace, "network_applied")
                    }
                } catch (throwable: Throwable) {
                    DeviceIdentityProbeFeature.logHit(
                        "evidence.getconf",
                        "trace=$trace,response_error=${throwable.javaClass.simpleName}," +
                            "elapsed=${SystemClock.elapsedRealtime()}",
                    )
                    throw throwable
                }
            }
        }
        return if (installed) 1 else 0
    }

    private fun hookGetconfApi(module: HookModule, classLoader: ClassLoader, phase: String): Int {
        val method = resolveOne(classLoader, DiagnosticsTargetKeys.getconfApi) ?: run {
            FeatureHooks.w("Device config evidence: exact getconf API unresolved, phase=$phase")
            return 0
        }
        val callClass = FeatureHooks.findClassOrNull("retrofit2.OkHttpCall", classLoader) ?: run {
            FeatureHooks.w("Device config evidence: Retrofit concrete call missing, phase=$phase")
            return 0
        }
        val enqueue = FeatureHooks.declaredMethodsNamed(callClass, "enqueue").singleOrNull { candidate ->
            !Modifier.isAbstract(candidate.modifiers) &&
                candidate.parameterTypes.size == 1 &&
                candidate.parameterTypes[0].name == "retrofit2.Callback" &&
                candidate.returnType == Void.TYPE
        } ?: run {
            FeatureHooks.w("Device config evidence: Retrofit enqueue unresolved, phase=$phase")
            return 0
        }
        val installed = FeatureHooks.hookAround(
            module = module,
            executable = enqueue,
            feature = "probe.device.getconf_api",
            id = "probe.device.getconf_api:${enqueue.toGenericString()}",
        ) { chain ->
            // Hot path: every async Retrofit request in the host passes through here. Keep the
            // non-getconf case down to two cached field reads plus one map lookup; annotation
            // scanning happens once per service Method and is then memoized.
            val serviceMethod = retrofitServiceMethod(chain.thisObject)
            if (serviceMethod == null || !isGetconfServiceMethod(serviceMethod)) {
                return@hookAround chain.proceed()
            }
            val trace = configContext.get()?.substringAfterLast(':') ?: nextTrace()
            val apiArgs = retrofitCallArgs(chain.thisObject)
            DeviceIdentityProbeFeature.logHit(
                "evidence.getconf_api",
                "trace=$trace,begin,method=${method.name}," +
                    "localLabels=${IdentityEvidenceCodec.fingerprint(apiArgs.getOrNull(0) as? String)}," +
                    "gender=${IdentityEvidenceCodec.safeFlag(apiArgs.getOrNull(1) as? String)}," +
                    "elapsed=${SystemClock.elapsedRealtime()}",
            )
            try {
                chain.proceed().also { result ->
                    DeviceIdentityProbeFeature.logHit(
                        "evidence.getconf_api",
                        "trace=$trace,end,result=${typeLabel(result)},elapsed=${SystemClock.elapsedRealtime()}",
                    )
                }
            } catch (throwable: Throwable) {
                DeviceIdentityProbeFeature.logHit(
                    "evidence.getconf_api",
                    "trace=$trace,error=${throwable.javaClass.simpleName},elapsed=${SystemClock.elapsedRealtime()}",
                )
                throw throwable
            }
        }
        return if (installed) 1 else 0
    }

    /** Hooks one resolver-verified AppConfig stage callback (cached file vs. network response). */
    private fun hookAppConfigStage(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
        key: TargetKey.MethodTarget,
        stage: String,
    ): Int {
        val method = resolveOne(classLoader, key) ?: run {
            FeatureHooks.w("Device config evidence: AppConfig $stage stage unresolved, phase=$phase")
            return 0
        }
        val installed = FeatureHooks.hookAround(
            module = module,
            executable = method,
            feature = "probe.device.app_config_$stage",
            id = "probe.device.app_config_$stage:${method.toGenericString()}",
        ) { chain ->
            val trace = traceFor(chain.thisObject)
            withConfigContext("$stage:$trace") {
                latestConfigContext = "$stage:$trace"
                DeviceIdentityProbeFeature.logHit(
                    "evidence.app_config_stage",
                    "trace=$trace,stage=$stage,bean=${summarizeAppConfigBean(chain.args.firstOrNull())}," +
                        "elapsed=${SystemClock.elapsedRealtime()}",
                )
                chain.proceed().also {
                    if (stage == "cache") scheduleConfigFileSummary(trace, "cache_loaded")
                }
            }
        }
        return if (installed) 1 else 0
    }

    /** Summarizes a config payload off the host thread; see the call site in [hookConfigApply]. */
    private fun scheduleDecisionSummary(trace: String, stage: String, rawJson: String?) {
        fileWorker.execute {
            DeviceIdentityProbeFeature.logHit(
                "evidence.getconf",
                "trace=$trace,$stage,${ConfigDecisionSummary.summarize(rawJson)}",
            )
        }
    }

    private fun scheduleConfigFileSummary(trace: String, reason: String) {
        val context = HostSessionStore.contextOrNull() ?: return
        fileWorker.execute {
            val file = File(context.filesDir, APP_CONFIG_FILE)
            val decisions = if (file.isFile) {
                runCatching { ConfigDecisionSummary.summarize(file.readText(Charsets.UTF_8)) }
                    .getOrElse { "raw=[read_error:${it.javaClass.simpleName}],decisions=unknown" }
            } else {
                "raw=[missing],decisions=none"
            }
            DeviceIdentityProbeFeature.logHit(
                "evidence.getconf_file",
                "trace=$trace,reason=$reason,file=$APP_CONFIG_FILE," +
                    "${IdentityEvidenceCodec.fileFingerprint(file)},$decisions",
            )
        }
    }

    private fun summarizeAppConfigBean(bean: Any?): String {
        if (bean == null) return "null"
        val top = linkedMapOf<String, String>()
        listOf(
            "isFreshmanLimitFree",
            "isFreeReadingUser",
            "isReceiveFreeReading",
            "isNewDeviceForBubble",
        ).forEach { name -> top[name] = summarizeGetter(bean, name) }
        val freshman = readGetter(bean, "getFreshman")
        val nested = if (freshman == null) {
            "null"
        } else {
            listOf("isFreshman", "isNewDevice", "getShowLimitFreePopup", "getSource", "getExpiredTips")
                .joinToString(prefix = "{", postfix = "}") { name -> "$name=${summarizeGetter(freshman, name)}" }
        }
        return top.entries.joinToString(prefix = "{", postfix = ",getFreshman=$nested}") { (name, value) ->
            "$name=$value"
        }
    }

    private fun summarizeGetter(receiver: Any, name: String): String = summarizeScalar(readGetter(receiver, name))

    private fun readGetter(receiver: Any, name: String): Any? = runCatching {
        FeatureHooks.declaredMethodsNamed(receiver.javaClass, name)
            .firstOrNull { method -> method.parameterTypes.isEmpty() }
            ?.let { method -> FeatureHooks.invoke(method, receiver) }
    }.getOrNull()

    private fun summarizeScalar(value: Any?): String = when (value) {
        null -> "null"
        is Boolean, is Number -> value.toString()
        is String -> IdentityEvidenceCodec.safeFlag(value)
        else -> value.javaClass.simpleName
    }

    /**
     * True when [loader] is the config loader instance that owns `app_config.json`.
     *
     * The loader stores its target file name in the only String field of the hierarchy, so identity
     * is decided by that value rather than by the synthetic subclass name (which drifts). When the
     * field cannot be located the probe warns once instead of failing silently — a silent `false`
     * here would disable the whole getconf evidence chain with no trace in the log.
     */
    private fun isAppConfigLoader(loader: Any?): Boolean {
        if (loader == null) return false
        val name = configFileName(loader)
        if (name == null && unresolvedLoaderShapes.add(loader.javaClass.name)) {
            FeatureHooks.w(
                "Device config evidence: loader file field unresolved, " +
                    "type=${loader.javaClass.simpleName}; getconf evidence for it is skipped",
            )
        }
        return name == APP_CONFIG_FILE
    }

    private fun safeConfigFileName(loader: Any?): String = when (val name = configFileName(loader)) {
        null -> "unknown"
        APP_CONFIG_FILE -> APP_CONFIG_FILE
        else -> IdentityEvidenceCodec.fingerprint(name)
    }

    private fun configFileName(loader: Any?): String? {
        loader ?: return null
        val field = configFileFields[loader.javaClass] ?: findConfigFileField(loader.javaClass)?.also { found ->
            configFileFields[loader.javaClass] = found
        }
        return field?.let { runCatching { FeatureHooks.read(it, loader) as? String }.getOrNull() }
    }

    private fun findConfigFileField(type: Class<*>): Field? {
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            FeatureHooks.allDeclaredFields(current).singleOrNull { field -> field.type == String::class.java }?.let {
                return it
            }
            current = current.superclass
        }
        return null
    }

    /**
     * Memoized getconf test for the [hookGetconfApi] hot path.
     *
     * Retrofit caches one `ServiceMethod` per interface method, so the reflective [Method] is a
     * stable, bounded identity — safe as a map key and cleared on hot reload. Without this, every
     * host request would re-scan annotations and reflectively invoke `value()`.
     */
    private fun isGetconfServiceMethod(method: Method): Boolean =
        getconfServiceMethods.getOrPut(method) { hasExactGetconfPath(method) }

    private fun retrofitServiceMethod(call: Any?): Method? {
        call ?: return null
        val requestFactory = FeatureHooks.readDeclaredFieldValue(call, "requestFactory") ?: return null
        return FeatureHooks.readDeclaredFieldValue(requestFactory, "method") as? Method
    }

    private fun retrofitCallArgs(call: Any?): List<Any?> {
        call ?: return emptyList()
        val args = FeatureHooks.readDeclaredFieldValue(call, "args") as? Array<*> ?: return emptyList()
        return args.toList()
    }

    private fun hasExactGetconfPath(method: Method): Boolean = method.annotations.any { annotation ->
        annotation.annotationClass.java.name == "retrofit2.http.POST" && runCatching {
            annotation.annotationClass.java.getMethod("value").invoke(annotation)?.toString()
        }.getOrNull()?.equals(GETCONF_PATH, ignoreCase = true) == true
    }

    private fun isConfigRequestMethod(method: Method): Boolean =
        method.declaringClass.name == HostSymbols.Classes.CONFIG_LOADER &&
            !Modifier.isStatic(method.modifiers) &&
            method.parameterTypes.size == 1 &&
            method.parameterTypes[0].isInterface &&
            method.returnType == Void.TYPE

    private fun isConfigApplyMethod(method: Method): Boolean =
        method.declaringClass.name == HostSymbols.Classes.CONFIG_LOADER &&
            !Modifier.isStatic(method.modifiers) &&
            method.parameterTypes.size == 2 &&
            method.parameterTypes[0] == String::class.java &&
            method.parameterTypes[1].isInterface &&
            method.returnType == Void.TYPE

    private fun traceFor(loader: Any?): String {
        val active = configContext.get()?.substringAfterLast(':')
        if (active != null) return active
        if (loader == null) return nextTrace()
        return loaderTraces[loader] ?: nextTrace().also { trace -> loaderTraces[loader] = trace }
    }

    private fun enqueueLoaderTrace(loader: Any, trace: String) {
        synchronized(pendingLoaderTraces) {
            pendingLoaderTraces.getOrPut(loader, ::ArrayDeque).addLast(trace)
        }
        loaderTraces[loader] = trace
    }

    private fun takeLoaderTrace(loader: Any?): String {
        val active = configContext.get()?.substringAfterLast(':')
        if (active != null) return active
        if (loader == null) return nextTrace()
        val pending = synchronized(pendingLoaderTraces) {
            val queue = pendingLoaderTraces[loader]
            val trace = queue?.pollFirst()
            if (queue?.isEmpty() == true) pendingLoaderTraces.remove(loader)
            trace
        }
        return pending ?: loaderTraces[loader] ?: nextTrace().also { loaderTraces[loader] = it }
    }

    private fun <T> withConfigContext(context: String, block: () -> T): T {
        val previous = configContext.get()
        configContext.set(context)
        return try {
            block()
        } finally {
            if (previous == null) configContext.remove() else configContext.set(previous)
        }
    }

    private fun resolveOne(
        classLoader: ClassLoader,
        key: TargetKey.MethodTarget,
    ): Method? = HostTargetResolver.resolveMethod(
        context = HostSessionStore.contextOrNull(),
        classLoader = classLoader,
        processName = HostSessionStore.processName,
        key = key,
    )

    private fun nextTrace(): String = "cfg-${traceSequence.incrementAndGet()}"

    private fun typeLabel(value: Any?): String = value?.javaClass?.simpleName ?: "null"
}
