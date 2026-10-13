package com.xiyunmn.qdhelper.feature.diagnostics.probe

import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.InstallableFeature
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols as QDReaderHost
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import java.lang.reflect.Array
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object TargetObjectShapeProbeFeature : InstallableFeature {
    override val name: String = "TargetObjectShapeProbeFeature"

    private const val FIRST_VERBOSE_HITS = 8L
    private const val SAMPLE_INTERVAL = 50L
    private const val MAX_FIELDS = 32
    private const val MAX_STRING = 96

    private val hitCounters = ConcurrentHashMap<String, AtomicLong>()

    private val targets = listOf(
        ShapeTarget(
            id = "bookshelf.config.light.shape",
            className = QDReaderHost.Classes.BOOKSHELF_CONFIG,
            methodName = "getLightMode",
            dumpReceiver = true,
            dumpResult = true,
        ),
        ShapeTarget(
            id = "bookshelf.config.dark.shape",
            className = QDReaderHost.Classes.BOOKSHELF_CONFIG,
            methodName = "getDarkMode",
            dumpReceiver = true,
            dumpResult = true,
        ),
        ShapeTarget(
            id = "bookshelf.config.copy.shape",
            className = QDReaderHost.Classes.BOOKSHELF_CONFIG,
            methodName = "copy",
            dumpReceiver = true,
            dumpArgs = true,
            dumpResult = true,
        ),
        ShapeTarget(
            id = "startImage.paper.image.shape",
            className = QDReaderHost.Classes.BOOT_PAPER,
            methodName = "getImageUrl",
            dumpReceiver = true,
            dumpResult = true,
        ),
        ShapeTarget(
            id = "startImage.paper.preview.shape",
            className = QDReaderHost.Classes.BOOT_PAPER,
            methodName = "getPreImageUrl",
            dumpReceiver = true,
            dumpResult = true,
        ),
        ShapeTarget(
            id = "startImage.paper.used.shape",
            className = QDReaderHost.Classes.BOOT_PAPER,
            methodName = "isUsed",
            dumpReceiver = true,
            dumpResult = true,
        ),
        ShapeTarget(
            id = "startImage.paper.obtain.shape",
            className = QDReaderHost.Classes.BOOT_PAPER,
            methodName = "isObtain",
            dumpReceiver = true,
            dumpResult = true,
        ),
        ShapeTarget(
            id = "startImage.viewModel.select.shape",
            className = QDReaderHost.Classes.SPLASH_CONFIG_SHARED_VIEW_MODEL,
            methodName = "search",
            dumpReceiver = true,
            dumpArgs = true,
            dumpResult = true,
        ),
        ShapeTarget(
            id = "startImage.viewModel.use.shape",
            className = QDReaderHost.Classes.SPLASH_CONFIG_SHARED_VIEW_MODEL,
            methodName = "c",
            dumpReceiver = true,
            dumpArgs = true,
            dumpResult = true,
        ),
        ShapeTarget(
            id = "startImage.viewModel.remote.shape",
            className = QDReaderHost.Classes.SPLASH_CONFIG_SHARED_VIEW_MODEL,
            methodName = "e",
            dumpReceiver = true,
            dumpArgs = true,
            dumpResult = true,
        ),
    )

    override fun install(
        module: HookModule,
        classLoader: ClassLoader,
        processName: String,
        phase: String,
    ) {
        var installedMethods = 0
        targets.forEach { target ->
            val clazz = FeatureHooks.findClassOrNull(target.className, classLoader) ?: return@forEach
            FeatureHooks.declaredMethodsNamed(clazz, target.methodName).forEach { method ->
                if (installProbe(module, target, method, phase)) {
                    installedMethods += 1
                }
            }
        }
        FeatureHooks.i("Object shape probe phase finished: phase=$phase, process=$processName, installedMethods=$installedMethods")
    }

    private fun installProbe(
        module: HookModule,
        target: ShapeTarget,
        method: Method,
        phase: String,
    ): Boolean {
        val installKey = "${target.id}:${method.toGenericString()}"
        val hookId = "probe.shape.${target.id}.${method.shortHash()}"
        return FeatureHooks.hookAround(
            module = module,
            executable = method,
            feature = "probe.shape.${target.id}:${method.ownerLabel()}.${method.name}",
            id = hookId,
        ) { chain ->
            if (!com.xiyunmn.qdhelper.config.SettingsRuntime.isCallProbeEnabled()) return@hookAround chain.proceed()
            val count = hitCounters.computeIfAbsent(installKey) { AtomicLong(0L) }.incrementAndGet()
            val verbose = shouldLogHit(count)
            if (verbose && target.dumpReceiver) {
                FeatureHooks.i("Probe shape receiver ${target.id}#$count: ${snapshot(chain.thisObject)}")
            }
            if (verbose && target.dumpArgs && chain.args.isNotEmpty()) {
                FeatureHooks.i("Probe shape args ${target.id}#$count: ${chain.args.mapIndexed { index, arg -> "arg$index=${snapshot(arg)}" }}")
            }

            val result = chain.proceed()
            if (verbose && target.dumpResult) {
                FeatureHooks.i("Probe shape result ${target.id}#$count: ${snapshot(result)}")
            }
            result
        }.also { success ->
            if (success) {
                FeatureHooks.i("Object shape probe installed ${target.id}: ${method.ownerLabel()}.${method.name}, phase=$phase")
            }
        }
    }

    private fun shouldLogHit(count: Long): Boolean {
        return count <= FIRST_VERBOSE_HITS || count % SAMPLE_INTERVAL == 0L
    }

    private fun snapshot(value: Any?): String {
        if (value == null) {
            return "null"
        }
        return runCatching {
            when (value) {
                is String -> "String(len=${value.length}, value=${value.safeString()})"
                is Number, is Boolean, is Char -> "${value.javaClass.simpleName}($value)"
                is Enum<*> -> "${value.javaClass.name}.${value.name}"
                is Collection<*> -> collectionSummary(value)
                is Map<*, *> -> "Map(${value.javaClass.name}, size=${value.size})"
                else -> {
                    val clazz = value.javaClass
                    if (clazz.isArray) {
                        "Array(${clazz.componentType?.name}, length=${Array.getLength(value)})"
                    } else {
                        objectSummary(value, clazz)
                    }
                }
            }
        }.getOrElse { throwable ->
            "snapshotError(${throwable.javaClass.name})"
        }
    }

    private fun objectSummary(value: Any, clazz: Class<*>): String {
        val fields = allInstanceFields(clazz).take(MAX_FIELDS)
        if (fields.isEmpty()) {
            return "${clazz.name}{}"
        }
        val body = fields.joinToString(separator = ", ") { field ->
            "${field.name}=${field.safeValueSummary(value)}"
        }
        val truncated = if (allInstanceFields(clazz).size > MAX_FIELDS) ", ..." else ""
        return "${clazz.name}{$body$truncated}"
    }

    private fun allInstanceFields(clazz: Class<*>): List<Field> {
        val fields = mutableListOf<Field>()
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            FeatureHooks.allDeclaredFields(current)
                .filterNot { field -> Modifier.isStatic(field.modifiers) }
                .forEach(fields::add)
            current = current.superclass
        }
        return fields
    }

    private fun Field.safeValueSummary(owner: Any): String {
        return runCatching {
            val value = get(owner) ?: return@runCatching "null"
            when (value) {
                is String -> "String(len=${value.length}, value=${value.safeString()})"
                is Number, is Boolean, is Char -> value.toString()
                is Enum<*> -> value.name
                is Collection<*> -> collectionSummary(value)
                is Map<*, *> -> "Map(size=${value.size})"
                else -> {
                    val clazz = value.javaClass
                    if (clazz.isArray) {
                        "Array(length=${Array.getLength(value)})"
                    } else {
                        QDReaderHost.classLabel(clazz.name)
                    }
                }
            }
        }.getOrElse { "inaccessible(${it.javaClass.simpleName})" }
    }

    private fun collectionSummary(value: Collection<*>): String {
        val sampleTypes = value.asSequence()
            .take(3)
            .map { item -> item?.javaClass?.name?.let(QDReaderHost::classLabel) ?: "null" }
            .joinToString(prefix = "[", postfix = "]")
        return "Collection(${value.javaClass.name}, size=${value.size}, sampleTypes=$sampleTypes)"
    }

    private fun String.safeString(): String {
        val compact = replace('\n', ' ').replace('\r', ' ')
        val truncated = if (compact.length > MAX_STRING) compact.take(MAX_STRING) + "..." else compact
        return when {
            contains("token", ignoreCase = true) -> "<redacted-token-like>"
            contains("cookie", ignoreCase = true) -> "<redacted-cookie-like>"
            contains("password", ignoreCase = true) -> "<redacted-password-like>"
            else -> truncated
        }
    }

    private fun Method.ownerLabel(): String {
        return QDReaderHost.classLabel(declaringClass.name)
    }

    private fun Method.shortHash(): String {
        return Integer.toHexString(toGenericString().hashCode())
    }
}

private data class ShapeTarget(
    val id: String,
    val className: String,
    val methodName: String,
    val dumpReceiver: Boolean = false,
    val dumpArgs: Boolean = false,
    val dumpResult: Boolean = false,
)
