package com.xiyunmn.qdhelper.feature.networkidentity

import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
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

object CustomDnsFeature : InstallableFeature {
    override val name: String = "CustomDnsFeature"

    private val hitCounters = ConcurrentHashMap<String, AtomicLong>()
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
        FeatureHooks.i("DNS phase finished: phase=$phase, process=$processName, installedHooks=$installedHooks")
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
            feature = "p3.networkDns.$id:${method.ownerLabel()}.${method.name}",
            id = "qdhelper.p3.$id.${method.name}.${Integer.toHexString(method.toGenericString().hashCode())}",
        ) { chain ->
            around(chain, installKey)
        }
        if (!success) {
            return 0
        }
        FeatureHooks.i("DNS installed $id: ${method.ownerLabel()}.${method.name}, phase=$phase")
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
                FeatureHooks.w("DNS missing class $id: ${QDReaderHost.classLabel(className)}, phase=$phase")
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
            "DNS missing method $id: ${QDReaderHost.classLabel(clazz.name)}.$name/" +
                parameterTypes.size + ", phase=$phase",
        )
        return null
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
