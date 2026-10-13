package com.xiyunmn.qdhelper.feature.diagnostics.cookie

import android.content.Context
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols as QDReaderHost
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Read-only Tianyu/TuringFD state.  This class never calls init(), a native collector, or a
 * network API.  It only inspects state which the host has already materialized in memory or in
 * its encrypted local cache.
 */
object TianyuDiagnostics {
    data class Entry(val name: String, val value: String, val sensitive: Boolean = false)

    data class Info(
        val available: Boolean,
        val sdkPresent: Boolean,
        val sdkVersion: String? = null,
        val libraryLoaded: Boolean? = null,
        val initialized: Boolean? = null,
        val nativeInitialized: Boolean? = null,
        val attestation: TianyuAttestationDiagnostics.Info,
        val cachedState: List<Entry> = emptyList(),
        val detectionResults: List<Entry> = emptyList(),
        val configuredDetectors: List<Entry> = emptyList(),
        val cachedRisk: List<Entry> = emptyList(),
        val error: String? = null,
    ) {
        fun toText(maskSensitive: Boolean): String = buildString {
            append("—— 天御 TuringFD ——\n")
            if (!available) {
                append("结果不可用：")
                append(error ?: "宿主未加载 TuringFD")
                append("\n未主动初始化 SDK、生成密钥或发起风险请求")
                return@buildString
            }
            append("SDK 类: ")
            append(if (sdkPresent) "存在" else "不存在")
            append("\n版本: ")
            append(sdkVersion ?: "未读取")
            append("\nlibturingga.so: ")
            append(libraryLoaded.asState())
            append("\nSDK init: ")
            append(initialized.asState())
            append("\nnative init: ")
            append(nativeInitialized.asState())
            append("\n\n")
            append(attestation.toText(maskSensitive))
            append("\n\n—— 天御缓存状态（未触发采集）——\n")
            appendEntries(cachedState, maskSensitive)
            append("\n\n—— 天御服务端检测策略原始值 ——\n")
            appendEntries(detectionResults, maskSensitive)
            append("\n\n—— 天御检测集合 ——\n")
            appendEntries(configuredDetectors, maskSensitive)
            append("\n\n—— 天御设备 token 缓存元数据 ——\n")
            appendEntries(cachedRisk, maskSensitive)
            append("\n\n逐项 native 命中结果: SDK 未公开或缓存，仅封装进加密风险请求 payload")
            append("\n说明：策略编号和值由宿主 TuringFD 定义；模块不将未知值解释为通过/失败。")
        }

        private fun StringBuilder.appendEntries(entries: List<Entry>, maskSensitive: Boolean) {
            if (entries.isEmpty()) {
                append("未返回（没有已缓存结果）")
                return
            }
            entries.forEachIndexed { index, entry ->
                if (index > 0) append('\n')
                append(entry.name)
                append(": ")
                append(if (entry.sensitive && maskSensitive) "<redacted>" else entry.value)
            }
        }

        private fun Boolean?.asState(): String = when (this) {
            true -> "是"
            false -> "否"
            null -> "不可用"
        }

        companion object {
            fun unavailable(error: String? = null): Info = Info(
                available = false,
                sdkPresent = false,
                attestation = TianyuAttestationDiagnostics.Info.unavailable(),
                error = error,
            )
        }
    }

    fun read(context: Context): Info = runCatching {
        val loader = context.classLoader ?: return Info.unavailable("宿主 ClassLoader 不可用")
        val sdkClass = find(loader, QDReaderHost.Classes.TURING_SDK)
        val pear = find(loader, QDReaderHost.Classes.TURING_CONFIG)
        val sugarcane = find(loader, QDReaderHost.Classes.TURING_RUNTIME)
        val rambutan = find(loader, QDReaderHost.Classes.TURING_NATIVE_CACHE)
        val raspberry = find(loader, QDReaderHost.Classes.TURING_DETECTOR_STATE)
        val pomelo = find(loader, QDReaderHost.Classes.TURING_RISK_CACHE)
        val sdkVersion = sdkClass?.let { version(it) }
        val sugarcaneStates = sugarcane?.let(::staticAtomicBooleans).orEmpty()
        val libraryLoaded = sugarcaneStates.getOrNull(0)
        val initialized = pear?.let(::staticSelf)?.let { true } ?: pear?.let { false }
        val nativeInitialized = sugarcaneStates.getOrNull(1)
        val attestation = TianyuAttestationDiagnostics.read(context)
        val cachedState = readCachedState(rambutan)
        val detectionResults = readDetectionResults(rambutan)
        val configuredDetectors = readDetectorSets(raspberry)
        val cachedRisk = readCachedRisk(pomelo, context)
        val available = sdkClass != null || pear != null || sugarcane != null || rambutan != null ||
            raspberry != null || pomelo != null || attestation.available
        Info(
            available = available,
            sdkPresent = sdkClass != null,
            sdkVersion = sdkVersion,
            libraryLoaded = libraryLoaded,
            initialized = initialized,
            nativeInitialized = nativeInitialized,
            attestation = attestation,
            cachedState = cachedState,
            detectionResults = detectionResults,
            configuredDetectors = configuredDetectors,
            cachedRisk = cachedRisk,
        )
    }.getOrElse { throwable ->
        Info.unavailable("${throwable.javaClass.simpleName}: ${throwable.message.orEmpty()}".trimEnd(':', ' '))
    }

    private fun readCachedState(clazz: Class<*>?): List<Entry> {
        val state = readNativeCache(clazz) ?: return emptyList()
        val fields = instanceFields(state)
        val longs = fields.filter { it.type == Long::class.javaPrimitiveType }
        val boolean = fields.singleOrNull { it.type == Boolean::class.javaPrimitiveType }
        return listOfNotNull(
            entry("状态序号", longs.getOrNull(0)?.let { FeatureHooks.read(it, state) }),
            entry("状态可用", boolean?.let { FeatureHooks.read(it, state) }),
            entry("状态版本", longs.getOrNull(1)?.let { FeatureHooks.read(it, state) }),
            entry("缓存有效期(ms)", longs.getOrNull(2)?.let { FeatureHooks.read(it, state) }),
        )
    }

    private fun readDetectionResults(clazz: Class<*>?): List<Entry> {
        val state = readNativeCache(clazz) ?: return emptyList()
        val maps = instanceFields(state).filter { Map::class.java.isAssignableFrom(it.type) }
        val result = mutableListOf<Entry>()
        addMap(result, "策略附加字段", maps.getOrNull(0)?.let { FeatureHooks.read(it, state) }, sensitive = true)
        addMap(result, "策略项", maps.getOrNull(1)?.let { FeatureHooks.read(it, state) }, sensitive = false)
        addMap(result, "服务端特征", maps.getOrNull(2)?.let { FeatureHooks.read(it, state) }, sensitive = true)
        return result
    }

    private fun readDetectorSets(clazz: Class<*>?): List<Entry> {
        val singleton = clazz?.let(::staticSelf) ?: return emptyList()
        val sets = instanceFields(singleton).filter { Set::class.java.isAssignableFrom(it.type) }
        val defaultSet = FeatureHooks.allDeclaredFields(clazz).singleOrNull { field ->
            Modifier.isStatic(field.modifiers) && Set::class.java.isAssignableFrom(field.type)
        }?.let { FeatureHooks.read(it, null) }
        return listOfNotNull(
            setEntry("默认检测集合", defaultSet),
            setEntry("当前检测集合", sets.getOrNull(2)?.let { FeatureHooks.read(it, singleton) }),
            setEntry("强制启用集合", sets.getOrNull(1)?.let { FeatureHooks.read(it, singleton) }),
            setEntry("禁用集合", sets.getOrNull(0)?.let { FeatureHooks.read(it, singleton) }),
        )
    }

    private fun readCachedRisk(clazz: Class<*>?, context: Context): List<Entry> {
        val singleton = clazz?.let(::staticSelf) ?: return emptyList()
        val reference = instanceFields(singleton)
            .filter { field -> AtomicReference::class.java.isAssignableFrom(field.type) }
            .getOrNull(0)
            ?.let { FeatureHooks.read(it, singleton) } as? AtomicReference<*> ?: return emptyList()
        val risk = reference.get() ?: FeatureHooks.allDeclaredMethods(clazz).singleOrNull { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.parameterTypes.contentEquals(arrayOf(Context::class.java)) &&
                method.returnType.simpleName == "Pineapple"
        }?.let { FeatureHooks.invoke(it, singleton, context) } ?: return emptyList()
        val fields = instanceFields(risk)
        val strings = fields.filter { it.type == String::class.java }
        val longs = fields.filter { it.type == Long::class.javaPrimitiveType }
        val ints = fields.filter { it.type == Int::class.javaPrimitiveType }
        return listOfNotNull(
            entry("缓存 code", ints.getOrNull(0)?.let { FeatureHooks.read(it, risk) }),
            entry("有效期(epoch 秒)", longs.getOrNull(0)?.let { FeatureHooks.read(it, risk) }),
            entry("阶段 1", ints.getOrNull(1)?.let { FeatureHooks.read(it, risk) }),
            entry("阶段 2", ints.getOrNull(2)?.let { FeatureHooks.read(it, risk) }),
            entry(
                "服务端 token",
                if ((strings.getOrNull(0)?.let { FeatureHooks.read(it, risk) } as? String).isNullOrBlank()) {
                    "未返回"
                } else {
                    "已返回"
                },
            ),
            *strings.drop(1).mapIndexed { index, field ->
                val present = !(FeatureHooks.read(field, risk) as? String).isNullOrBlank()
                Entry("响应附加字段 ${index + 1}", if (present) "已返回（内容不显示）" else "未返回")
            }.toTypedArray(),
        )
    }

    private fun readNativeCache(clazz: Class<*>?): Any? {
        val singleton = clazz?.let(::staticSelf) ?: return null
        val stateField = instanceFields(singleton).singleOrNull { field ->
            field.type.simpleName == "Csynchronized"
        } ?: return null
        FeatureHooks.read(stateField, singleton)?.let { return it }
        val cacheReader = FeatureHooks.allDeclaredMethods(clazz).singleOrNull { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.parameterTypes.isEmpty() &&
                method.returnType == stateField.type
        } ?: return null
        return FeatureHooks.invoke(cacheReader, singleton)
    }

    private fun addMap(target: MutableList<Entry>, prefix: String, value: Any?, sensitive: Boolean) {
        val map = value as? Map<*, *> ?: return
        map.entries.sortedBy { it.key.toString() }.forEach { (key, item) ->
            target += Entry("$prefix[$key]", item?.toString() ?: "null", sensitive)
        }
    }

    private fun entry(name: String, value: Any?, sensitive: Boolean = false): Entry? =
        value?.let { Entry(name, it.toString(), sensitive) }

    private fun setEntry(name: String, value: Any?): Entry? = value?.let {
        val set = it as? Set<*> ?: return@let null
        Entry(
            name,
            set.map { item -> item.toString() }
                .sortedWith(compareBy<String> { item -> item.toLongOrNull() ?: Long.MAX_VALUE }.thenBy { it })
                .joinToString(", "),
        )
    }

    private fun version(clazz: Class<*>): String? = runCatching {
        FeatureHooks.allDeclaredMethods(clazz).singleOrNull { method ->
            Modifier.isStatic(method.modifiers) &&
                method.name == "getVersionInfo" &&
                method.parameterTypes.isEmpty() &&
                method.returnType == String::class.java
        }?.let { FeatureHooks.invoke(it, null)?.toString() }
    }.getOrNull()

    private fun atomic(value: Any?): Boolean? = when (value) {
        is AtomicBoolean -> value.get()
        is Boolean -> value
        else -> null
    }

    private fun staticAtomicBooleans(clazz: Class<*>): List<Boolean> =
        FeatureHooks.allDeclaredFields(clazz)
            .filter { Modifier.isStatic(it.modifiers) && AtomicBoolean::class.java.isAssignableFrom(it.type) }
            .mapNotNull { atomic(FeatureHooks.read(it, null)) }

    private fun staticSelf(clazz: Class<*>): Any? =
        FeatureHooks.allDeclaredFields(clazz).singleOrNull { field ->
            Modifier.isStatic(field.modifiers) && field.type == clazz
        }?.let { FeatureHooks.read(it, null) }

    private fun instanceFields(instance: Any) = FeatureHooks.allDeclaredFields(instance.javaClass)
        .filterNot { Modifier.isStatic(it.modifiers) }

    private fun find(loader: ClassLoader, name: String): Class<*>? =
        FeatureHooks.findClassOrNull(name, loader)
}
