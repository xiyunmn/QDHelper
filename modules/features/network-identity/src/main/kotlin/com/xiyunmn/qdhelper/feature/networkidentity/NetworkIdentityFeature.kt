package com.xiyunmn.qdhelper.feature.networkidentity

import android.content.Context
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.host.api.target.HostTargetResolver
import com.xiyunmn.qdhelper.host.api.target.NetworkIdentityTargetKeys
import com.xiyunmn.qdhelper.feature.api.runtime.InstallableFeature
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols as QDReaderHost
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.feature.api.runtime.HookChain
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import java.lang.reflect.Method
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

object NetworkIdentityFeature : InstallableFeature {
    override val name: String = "NetworkIdentityFeature"

    private val hitCounters = ConcurrentHashMap<String, AtomicLong>()
    private val hostQimeiSyncSignature = AtomicReference("")
    override fun install(
        module: HookModule,
        classLoader: ClassLoader,
        processName: String,
        phase: String,
    ) {
        if (!QDReaderHost.isMainProcess(processName)) return
        val context = HostSessionStore.contextOrNull()
        var installedHooks = 0
        if (SettingsRuntime.isCustomDnsEnabled(context)) {
            installedHooks += installHttpDnsHosts(module, classLoader, phase)
            installedHooks += installDnsNetStack(module, classLoader, phase)
            installedHooks += installDnsLookup(
                module,
                classLoader,
                QDReaderHost.Classes.TX_HTTP_DNS_HELPER,
                "network.dns.main",
                phase,
            )
            installedHooks += installDnsLookup(
                module,
                classLoader,
                QDReaderHost.Classes.AUDIO_TX_HTTP_DNS_HELPER,
                "network.dns.audio",
                phase,
            )
        }
        if (SettingsRuntime.isCustomQimeiEnabled(context)) {
            installedHooks += installCommonQimeiGetters(module, classLoader, phase)
            installedHooks += installCommonQimeiCacheGetters(module, classLoader, phase)
            installedHooks += installFakeIdentityGetters(module, classLoader, phase)
            installedHooks += installPushQimei(module, classLoader, phase)
            if (phase != "hotReloaded") {
                installedHooks += installDeviceInfoGetters(module, classLoader, phase)
            }
            syncHostQimeiState(classLoader, phase)
        }
        if (SettingsRuntime.isCustomGuidEnabled(context)) {
            installedHooks += installAccountGuidGetter(module, classLoader, phase)
        }
        if (SettingsRuntime.isMultiBoxReportShieldEnabled(context)) {
            installedHooks += installMultiBoxReportShield(module, classLoader, phase)
        }
        FeatureHooks.i("Network/identity phase finished: phase=$phase, process=$processName, installedHooks=$installedHooks")
    }

    private fun installHttpDnsHosts(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
    ): Int {
        val clazz = findClassOrReport(QDReaderHost.Classes.QD_APP_CONFIG_HELPER_COMPANION, classLoader, "network.dns.hosts", phase)
            ?: return 0
        val method = findNoArgMethodOrReport(clazz, "getHttpDnsHosts", "network.dns.hosts", phase) ?: return 0
        return installAround(module, "network.dns.hosts", method, phase) { chain, installKey ->
            val rules = DnsRules.parse(SettingsRuntime.customDnsRules(HostSessionStore.contextOrNull()))
            if (!SettingsRuntime.isCustomDnsEnabled(HostSessionStore.contextOrNull()) || rules.isEmpty()) {
                return@installAround chain.proceed()
            }
            val original = chain.proceed() as? List<*> ?: emptyList<Any?>()
            val merged = (original.mapNotNull { it as? String } + rules.hosts()).distinct()
            logHit("network.dns.hosts", installKey, "return-custom-hosts")
            ArrayList(merged)
        }
    }

    private fun installDnsNetStack(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
    ): Int {
        val clazz = findClassOrReport(QDReaderHost.Classes.TENCENT_DNS_CONFIG_BUILDER, classLoader, "network.dns.netStack", phase)
            ?: return 0
        val method = findMethodOrReport(clazz, "setCustomNetStack", "network.dns.netStack", phase, Int::class.javaPrimitiveType!!)
            ?: return 0
        return installAround(module, "network.dns.netStack", method, phase) { chain, installKey ->
            if (!SettingsRuntime.isCustomDnsEnabled(HostSessionStore.contextOrNull())) {
                return@installAround chain.proceed()
            }
            val netStack = SettingsRuntime.customDnsNetStack(HostSessionStore.contextOrNull())
            val args = chain.args.toMutableList()
            args[0] = netStack
            logHit("network.dns.netStack", installKey, "replace-net-stack")
            chain.proceed(args.toTypedArray())
        }
    }

    private fun installDnsLookup(
        module: HookModule,
        classLoader: ClassLoader,
        className: String,
        id: String,
        phase: String,
    ): Int {
        val clazz = findClassOrReport(className, classLoader, id, phase) ?: return 0
        val method = findMethodOrReport(clazz, "lookup", id, phase, String::class.java) ?: return 0
        return installAround(module, id, method, phase) { chain, installKey ->
            val hostname = chain.args.getOrNull(0) as? String
            val rules = DnsRules.parse(SettingsRuntime.customDnsRules(HostSessionStore.contextOrNull()))
            val addresses = if (SettingsRuntime.isCustomDnsEnabled(HostSessionStore.contextOrNull())) {
                rules.addressesFor(hostname)
            } else {
                emptyList()
            }
            if (addresses.isEmpty()) {
                return@installAround chain.proceed()
            }
            logHit(id, installKey, "return-custom-addresses")
            addresses
        }
    }

    private fun installCommonQimeiGetters(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
    ): Int {
        val clazz = findClassOrReport(QDReaderHost.Classes.COMMON_LIB_DEVICE, classLoader, "identity.qimei.common", phase)
        var installedHooks = 0
        installedHooks += installStringGetter(
            module,
            resolveHostMethod(
                classLoader,
                NetworkIdentityTargetKeys.commonDeviceQimei16,
            ),
            "identity.qimei.common16",
            phase,
        ) {
            qimei16OrNull()
        }
        installedHooks += installStringGetter(
            module,
            resolveHostMethod(
                classLoader,
                NetworkIdentityTargetKeys.commonDeviceQimei36,
            ),
            "identity.qimei.common36",
            phase,
        ) {
            qimei36OrNull()
        }
        return installedHooks
    }

    private fun installCommonQimeiCacheGetters(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
    ): Int {
        val clazz = findClassOrReport(QDReaderHost.Classes.COMMON_LIB_DEVICE, classLoader, "identity.qimei.cache", phase)
            ?: return 0
        var installedHooks = 0
        installedHooks += installStringGetter(
            module,
            resolveHostMethod(classLoader, NetworkIdentityTargetKeys.cachedBeacon16),
            "identity.qimei.beacon16",
            phase,
        ) {
            qimei16OrNull()
        }
        installedHooks += installStringGetter(
            module,
            resolveHostMethod(classLoader, NetworkIdentityTargetKeys.cachedBeacon36),
            "identity.qimei.beacon36",
            phase,
        ) {
            qimei36OrNull()
        }
        installedHooks += installStringGetter(
            module,
            resolveHostMethod(classLoader, NetworkIdentityTargetKeys.cachedOstar16),
            "identity.qimei.ostar16",
            phase,
        ) {
            qimei16OrNull()
        }
        installedHooks += installStringGetter(
            module,
            resolveHostMethod(classLoader, NetworkIdentityTargetKeys.cachedOstar36),
            "identity.qimei.ostar36",
            phase,
        ) {
            qimei36OrNull()
        }
        return installedHooks
    }

    private fun installFakeIdentityGetters(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
    ): Int {
        val clazz = findClassOrReport(QDReaderHost.Classes.COMMON_LIB_FAKE_IDENTITY, classLoader, "identity.qimei.fake", phase)
            ?: return 0
        var installedHooks = 0
        installedHooks += installStringGetter(
            module,
            resolveHostMethod(classLoader, NetworkIdentityTargetKeys.fakeLocal16),
            "identity.qimei.fakeLocal16",
            phase,
        ) {
            qimei16OrNull()
        }
        installedHooks += installStringGetter(
            module,
            resolveHostMethod(classLoader, NetworkIdentityTargetKeys.fakeLocal36),
            "identity.qimei.fakeLocal36",
            phase,
        ) {
            qimei36OrNull()
        }
        installedHooks += installStringGetter(
            module,
            resolveHostMethod(classLoader, NetworkIdentityTargetKeys.fakeOstar16),
            "identity.qimei.fakeOstar16",
            phase,
        ) {
            qimei16OrNull()
        }
        installedHooks += installStringGetter(
            module,
            resolveHostMethod(classLoader, NetworkIdentityTargetKeys.fakeOstar36),
            "identity.qimei.fakeOstar36",
            phase,
        ) {
            qimei36OrNull()
        }
        installedHooks += installStringGetter(
            module,
            resolveHostMethod(classLoader, NetworkIdentityTargetKeys.fake16),
            "identity.qimei.fake16",
            phase,
        ) {
            qimei16OrNull()
        }
        installedHooks += installStringGetter(
            module,
            resolveHostMethod(classLoader, NetworkIdentityTargetKeys.fake36),
            "identity.qimei.fake36",
            phase,
        ) {
            qimei36OrNull()
        }
        return installedHooks
    }

    private fun installPushQimei(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
    ): Int {
        val clazz = findClassOrReport(QDReaderHost.Classes.YW_PUSH_SDK, classLoader, "identity.qimei.push", phase)
            ?: return 0
        var installedHooks = 0
        installedHooks += installStringGetter(module, findNoArgMethodOrReport(clazz, "getQimei", "identity.qimei.push16", phase), "identity.qimei.push16", phase) {
            qimei16OrNull()
        }
        installedHooks += installStringGetter(module, findNoArgMethodOrReport(clazz, "getQimei36", "identity.qimei.push36", phase), "identity.qimei.push36", phase) {
            qimei36OrNull()
        }
        if (phase != "hotReloaded") {
            FeatureHooks.declaredMethodsNamed(clazz, "registerPush")
                .filter { method -> method.parameterTypes.size >= 3 }
                .forEach { method ->
                    installedHooks += installAround(module, "identity.qimei.pushRegister", method, phase) { chain, installKey ->
                        if (!SettingsRuntime.isCustomQimeiEnabled(HostSessionStore.contextOrNull())) {
                            return@installAround chain.proceed()
                        }
                        val args = chain.args.toMutableList()
                        qimei16OrNull()?.let { args[1] = it }
                        qimei36OrNull()?.let { args[2] = it }
                        logHit("identity.qimei.pushRegister", installKey, "replace-args")
                        chain.proceed(args.toTypedArray())
                    }
                }
        } else {
            FeatureHooks.i("Network/identity skip push register hooks on hot reload")
        }
        if (phase != "hotReloaded") {
            FeatureHooks.findDeclaredMethodOrNull(clazz, "setQimei", String::class.java)?.let { method ->
                installedHooks += installQimeiSetter(module, method, "identity.qimei.pushSet16", phase) { qimei16OrNull() }
            }
            FeatureHooks.findDeclaredMethodOrNull(clazz, "setQimei36", String::class.java)?.let { method ->
                installedHooks += installQimeiSetter(module, method, "identity.qimei.pushSet36", phase) { qimei36OrNull() }
            }
        } else {
            FeatureHooks.i("Network/identity skip supplemental push setters on hot reload")
        }
        return installedHooks
    }

    private fun installDeviceInfoGetters(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
    ): Int {
        val clazz = findClassOrReport(QDReaderHost.Classes.DEVICE_INFO, classLoader, "identity.qimei.device", phase)
            ?: return 0
        var installedHooks = 0
        installedHooks += installStringGetter(module, findNoArgMethodOrReport(clazz, "getQimei", "identity.qimei.device16", phase), "identity.qimei.device16", phase) {
            qimei16OrNull()
        }
        installedHooks += installStringGetter(module, findNoArgMethodOrReport(clazz, "getQimei36", "identity.qimei.device36", phase), "identity.qimei.device36", phase) {
            qimei36OrNull()
        }
        return installedHooks
    }

    private fun installAccountGuidGetter(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
    ): Int {
        val clazz = findClassOrReport(QDReaderHost.Classes.QD_USER_MANAGER, classLoader, "identity.guid", phase)
        val method = resolveHostMethod(
            classLoader,
            NetworkIdentityTargetKeys.accountYwGuid,
        ) ?: return 0
        return installAround(module, "identity.guid", method, phase) { chain, installKey ->
            val replacement = if (SettingsRuntime.isCustomGuidEnabled(HostSessionStore.contextOrNull())) customGuidOrNull() else null
            if (replacement.isNullOrBlank()) {
                return@installAround chain.proceed()
            }
            logHit("identity.guid", installKey, "return-custom")
            replacement
        }
    }

    private val MULTI_BOX_JSON_KEYS = listOf(
        "obs_isMultiBoxByPkg",
        "obs_isMultiBoxByFileStr",
        "obs_isMultiBoxByParentDir",
        "obs_isMultiBoxByUid",
    )

    /**
     * Shields the host's multi-box detection from reaching the server or local diagnostics.
     *
     * The 4 `IS_MULTI_BOX_BY_*` slots (Subscript 34-37) are produced on-device by libnib/libknobs
     * native collection, normalized by [com.yuewen.nib.search.judian] into the 64-slot array, then
     * encrypted into the `ibex` field / global header by [com.yuewen.nib.search.cihai].  On an app
     * clone (e.g. ColorOS MultiApp User 998, App UID = userId*100000+idx) the `obs_isMultiBoxByUid`
     * flag flips true; there is no Java-side consumer, so neutralizing the two Java bridges is the
     * whole attack surface:
     *
     *   - `search.judian(JSONObject)`: sanitize the input JSON in place so the 64-slot array already
     *     carries `false` for these slots (covers every cihai → ibex upload path).
     *   - `NativeWrapper.mmr(String)`: sanitize the returned plaintext JSON so local reads (e.g.
     *     diagnostics) and any other mmr consumer see `false` too.
     *
     * Payloads that carry no multi-box key (or already `false`) pass through unchanged.
     */
    private fun installMultiBoxReportShield(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
    ): Int {
        var installedHooks = 0

        val searchClazz = findClassOrReport(
            QDReaderHost.Classes.NIB_SEARCH,
            classLoader,
            "multibox.judian",
            phase,
        )
        val judian = searchClazz?.let {
            findMethodOrReport(it, "judian", "multibox.judian", phase, JSONObject::class.java)
        }
        if (judian != null) {
            installedHooks += installAround(module, "multibox.judian", judian, phase) { chain, installKey ->
                (chain.args.getOrNull(0) as? JSONObject)?.let(::sanitizeMultiBoxJson)
                logHit("multibox.judian", installKey, "sanitize-input")
                chain.proceed()
            }
        }

        val wrapperClazz = findClassOrReport(
            QDReaderHost.Classes.NIB_NATIVE_WRAPPER,
            classLoader,
            "multibox.mmr",
            phase,
        )
        val mmr = wrapperClazz?.let {
            findMethodOrReport(it, "mmr", "multibox.mmr", phase, String::class.java)
        }
        if (mmr != null) {
            installedHooks += installAround(module, "multibox.mmr", mmr, phase) { chain, installKey ->
                val original = chain.proceed() as? ByteArray
                val sanitized = sanitizeMultiBoxPayload(original)
                if (sanitized != null) {
                    logHit("multibox.mmr", installKey, "sanitize-return")
                    return@installAround sanitized
                }
                original
            }
        }
        return installedHooks
    }

    private fun sanitizeMultiBoxJson(json: JSONObject) {
        MULTI_BOX_JSON_KEYS.forEach { key ->
            if (json.has(key)) {
                runCatching { json.put(key, false) }
            }
        }
    }

    private fun sanitizeMultiBoxPayload(original: ByteArray?): ByteArray? {
        if (original == null) return null
        return runCatching {
            val text = String(original, Charsets.UTF_8)
            val json = JSONObject(text)
            val flagged = MULTI_BOX_JSON_KEYS.any { key -> json.has(key) && json.optBoolean(key, false) }
            if (!flagged) return@runCatching null
            sanitizeMultiBoxJson(json)
            json.toString().toByteArray(Charsets.UTF_8)
        }.getOrNull()
    }

    private fun customGuidOrNull(): String? {
        return SettingsRuntime.customGuid(HostSessionStore.contextOrNull()).takeIf { isPlausibleGuid(it) }
    }

    private fun isPlausibleGuid(value: String): Boolean {
        return value.length in 4..64 &&
            value.all { it.isLetterOrDigit() || it == '-' || it == '_' }
    }

    private fun installStringGetter(
        module: HookModule,
        method: Method?,
        id: String,
        phase: String,
        value: () -> String?,
    ): Int {
        if (method == null) return 0
        return installAround(module, id, method, phase) { chain, installKey ->
            val replacement = if (SettingsRuntime.isCustomQimeiEnabled(HostSessionStore.contextOrNull())) value() else null
            if (replacement.isNullOrBlank()) {
                return@installAround chain.proceed()
            }
            logHit(id, installKey, "return-custom")
            replacement
        }
    }

    private fun installQimeiSetter(
        module: HookModule,
        method: Method,
        id: String,
        phase: String,
        value: () -> String?,
    ): Int {
        return installAround(module, id, method, phase) { chain, installKey ->
            val replacement = if (SettingsRuntime.isCustomQimeiEnabled(HostSessionStore.contextOrNull())) value() else null
            if (replacement.isNullOrBlank()) {
                return@installAround chain.proceed()
            }
            logHit(id, installKey, "replace-arg")
            chain.proceed(arrayOf(replacement))
        }
    }

    private fun installAround(
        module: HookModule,
        id: String,
        method: Method,
        phase: String,
        around: (HookChain, String) -> Any?,
    ): Int {
        val installKey = "$id:${method.toGenericString()}"
        val success = FeatureHooks.hookAround(
            module = module,
            executable = method,
            feature = "p3.networkIdentity.$id:${method.ownerLabel()}.${method.name}",
            id = "qdhelper.p3.$id.${method.name}.${Integer.toHexString(method.toGenericString().hashCode())}",
        ) { chain ->
            around(chain, installKey)
        }
        if (!success) {
            return 0
        }
        FeatureHooks.i("Network/identity installed $id: ${method.ownerLabel()}.${method.name}, phase=$phase")
        return 1
    }

    private fun findClassOrReport(
        className: String,
        classLoader: ClassLoader,
        id: String,
        phase: String,
    ): Class<*>? {
        return FeatureHooks.findClassOrNull(className, classLoader).also { clazz ->
            if (clazz == null) {
                FeatureHooks.w("Network/identity missing class $id: ${QDReaderHost.classLabel(className)}, phase=$phase")
            }
        }
    }

    private fun findNoArgMethodOrReport(
        clazz: Class<*>,
        name: String,
        id: String,
        phase: String,
    ): Method? {
        return findMethodOrReport(clazz, name, id, phase)
    }

    private fun findMethodOrReport(
        clazz: Class<*>,
        name: String,
        id: String,
        phase: String,
        vararg parameterTypes: Class<*>,
    ): Method? {
        val exact = FeatureHooks.findDeclaredMethodOrNull(clazz, name, *parameterTypes)
        if (exact != null) return exact
        val fallback = FeatureHooks.declaredMethodsNamed(clazz, name)
            .firstOrNull { method ->
                method.parameterTypes.size == parameterTypes.size &&
                    method.parameterTypes.zip(parameterTypes).all { (actual, expected) -> actual == expected }
            }
        if (fallback != null) return fallback
        FeatureHooks.w(
            "Network/identity missing method $id: ${QDReaderHost.classLabel(clazz.name)}.$name/" +
                parameterTypes.size + ", phase=$phase",
        )
        return null
    }

    private fun resolveHostMethod(
        classLoader: ClassLoader,
        key: com.xiyunmn.qdhelper.host.api.target.TargetKey.MethodTarget,
    ): Method? {
        return HostTargetResolver.resolveMethod(
            context = HostSessionStore.contextOrNull(),
            classLoader = classLoader,
            processName = HostSessionStore.processName,
            key = key,
        )
    }

    private fun qimei16OrNull(): String? {
        return SettingsRuntime.customQimei(HostSessionStore.contextOrNull()).takeIf { isPlausibleQimei(it, minLength = 8) }
    }

    private fun qimei36OrNull(): String? {
        return SettingsRuntime.customQimei36(HostSessionStore.contextOrNull())
            .ifBlank { SettingsRuntime.customQimei(HostSessionStore.contextOrNull()) }
            .takeIf { isPlausibleQimei(it, minLength = 16) }
    }

    private fun syncHostQimeiState(
        classLoader: ClassLoader,
        phase: String,
    ) {
        val context = HostSessionStore.contextOrNull() ?: return
        val enabled = SettingsRuntime.isCustomQimeiEnabled(context)
        val qimei16 = if (enabled) qimei16OrNull() else null
        val qimei36 = if (enabled) qimei36OrNull() else null
        val last16 = SettingsRuntime.lastSyncedCustomQimei(context)
        val last36 = SettingsRuntime.lastSyncedCustomQimei36(context)
        val signature = "${enabled}:${qimei16.orEmpty()}:${qimei36.orEmpty()}:$last16:$last36"
        if (hostQimeiSyncSignature.get() == signature) return

        val changed16 = syncHostStringKeys(
            context = context,
            classLoader = classLoader,
            keys = QIMEI16_HOST_KEYS,
            desired = qimei16,
            lastSynced = last16,
        )
        val changed36 = syncHostStringKeys(
            context = context,
            classLoader = classLoader,
            keys = QIMEI36_HOST_KEYS,
            desired = qimei36,
            lastSynced = last36,
        )
        if (SettingsRuntime.markSyncedCustomQimei(context, qimei16.orEmpty(), qimei36.orEmpty())) {
            hostQimeiSyncSignature.set(signature)
            FeatureHooks.i(
                "Network/identity synced host qimei state: phase=$phase, " +
                    "enabled=$enabled, changed16=$changed16, changed36=$changed36",
            )
        }
    }

    private fun syncHostStringKeys(
        context: Context,
        classLoader: ClassLoader,
        keys: List<String>,
        desired: String?,
        lastSynced: String,
    ): Int {
        var changed = 0
        if (!desired.isNullOrBlank()) {
            keys.forEach { key ->
                if (writeHostStringPreference(context, classLoader, key, desired)) {
                    changed++
                }
            }
            return changed
        }
        if (lastSynced.isBlank()) return 0
        keys.forEach { key ->
            val current = readHostStringPreference(context, classLoader, key) ?: return@forEach
            if (current == lastSynced && writeHostStringPreference(context, classLoader, key, "")) {
                changed++
            }
        }
        return changed
    }

    private fun readHostStringPreference(
        context: Context,
        classLoader: ClassLoader,
        key: String,
    ): String? {
        val clazz = FeatureHooks.findClassOrNull(QDReaderHost.Classes.COMMON_LIB_PREFS, classLoader) ?: return null
        val method = FeatureHooks.findDeclaredMethodOrNull(
            clazz,
            "k",
            Context::class.java,
            String::class.java,
            String::class.java,
        ) ?: return null
        return runCatching { method.invoke(null, context, key, "") as? String }
            .onFailure { throwable ->
                FeatureHooks.w("Network/identity read host pref failed: key=$key, error=${throwable.javaClass.name}")
            }
            .getOrNull()
    }

    private fun writeHostStringPreference(
        context: Context,
        classLoader: ClassLoader,
        key: String,
        value: String,
    ): Boolean {
        val clazz = FeatureHooks.findClassOrNull(QDReaderHost.Classes.COMMON_LIB_PREFS, classLoader) ?: return false
        val method = FeatureHooks.findDeclaredMethodOrNull(
            clazz,
            "u",
            Context::class.java,
            String::class.java,
            String::class.java,
        ) ?: return false
        return runCatching {
            method.invoke(null, context, key, value)
            true
        }.onFailure { throwable ->
            FeatureHooks.w("Network/identity write host pref failed: key=$key, error=${throwable.javaClass.name}")
        }.getOrDefault(false)
    }

    private fun isPlausibleQimei(value: String, minLength: Int): Boolean {
        return value.length >= minLength &&
            value.length <= 96 &&
            value.all { it.isLetterOrDigit() || it == '-' || it == '_' }
    }

    private fun logHit(id: String, installKey: String, action: String) {
        val count = hitCounters.computeIfAbsent(installKey) { AtomicLong(0L) }.incrementAndGet()
        if (count <= 20L || count % 50L == 0L) {
            FeatureHooks.i("P3 hit $id#$count: action=$action, thread=${Thread.currentThread().name}")
        }
    }

    private fun Method.ownerLabel(): String {
        return QDReaderHost.classLabel(declaringClass.name)
    }

    private val QIMEI16_HOST_KEYS = listOf(
        "KEY_FAKE_QIMEI",
        "KEY_FAKE_LOCAL_QIMEI",
        "KEY_FAKE_OSTAR",
        "BEACON_QIMEI",
        "O_STAR_Q_IMEI_16",
    )

    private val QIMEI36_HOST_KEYS = listOf(
        "KEY_FAKE_QIMEI36",
        "KEY_FAKE_LOCAL_QIMEI36",
        "KEY_FAKE_OSTAR36",
        "BEACON_QIMEI_36",
        "O_STAR_Q_IMEI_36",
    )

    private data class DnsRules(
        private val mapping: Map<String, List<InetAddress>>,
    ) {
        fun isEmpty(): Boolean = mapping.isEmpty()

        fun hosts(): List<String> = mapping.keys.toList()

        fun addressesFor(hostname: String?): List<InetAddress> {
            val normalized = hostname?.trim()?.lowercase().orEmpty()
            if (normalized.isBlank()) return emptyList()
            mapping[normalized]?.let { return it }
            return mapping.entries.firstOrNull { (host, _) ->
                normalized == host || normalized.endsWith(".$host")
            }?.value.orEmpty()
        }

        companion object {
            fun parse(raw: String): DnsRules {
                val map = linkedMapOf<String, List<InetAddress>>()
                raw.lineSequence()
                    .map { it.substringBefore('#').trim() }
                    .filter { it.isNotBlank() }
                    .forEach { line ->
                        val separator = when {
                            '=' in line -> '='
                            ' ' in line -> ' '
                            else -> return@forEach
                        }
                        val host = line.substringBefore(separator).trim().lowercase()
                        val ips = line.substringAfter(separator).split(',', ';', ' ')
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                            .mapNotNull { ip ->
                                runCatching { InetAddress.getByName(ip) }.getOrNull()
                            }
                        if (host.isNotBlank() && ips.isNotEmpty()) {
                            map[host] = ips
                        }
                    }
                return DnsRules(map)
            }
        }
    }
}
