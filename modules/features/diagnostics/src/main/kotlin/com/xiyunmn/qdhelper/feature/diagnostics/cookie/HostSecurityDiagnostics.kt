package com.xiyunmn.qdhelper.feature.diagnostics.cookie

import android.content.Context
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols as QDReaderHost
import org.json.JSONObject
import java.lang.reflect.Modifier

/**
 * Reads the host's own native security-diagnostics result without reimplementing its detectors.
 *
 * The active audited hosts expose the stable [com.yuewen.nib] Java bridge. Native `mmr` reads the
 * collector JSON from the host files directory and `search.judian` normalizes it into the
 * Subscript enum's 64 slots before the host encrypts it for `ibex`. We intentionally stop before
 * encryption: this is a local diagnostic readout, not a second upload path.
 */
object HostSecurityDiagnostics {
    private const val NIB_STORAGE_SUFFIX_ARMOR =
        "lg!0q^<n]G()U2!cN)=z@+ToL*AUmPm3tnoUTL2E"

    private val bootloaderNames = setOf(
        "RO_SECUREBOOT_LOCKSTATE",
        "RO_BOOT_FLASH_LOCKED",
        "VENDOR_BOOT_VBMETA_DEVICE_STATE",
        "VENDOR_BOOT_VERIFIEDBOOTSTATE",
        "RO_BOOT_VBMETA_DEVICE_STATE",
        "SYS_OEM_UNLOCK_ALLOWED",
        "SYS_LOCKSETTINGS_REMOTEAUTH_ENABLE",
        "ANDROIDBOOT_VERIFIEDSTATE",
    )

    private val rootNames = setOf(
        "IS_ROOT_BY_PATH",
        "IS_ROOT_BY_PACKAGE_NAME",
        "IS_ROOT_BY_MAGISK",
        "IS_ROOT_BY_ATTR",
    )

    private val sensitiveNames = setOf(
        "PLUGIN_CHARACTER",
        "PROP_SERIAL",
        "GUID",
        "QIMEI",
        "QIMEI36",
        "PACKAGE_NAME",
        "DEVICE_HASH",
        "SIGNATURE_MD5_FROM_SVC",
    )

    data class Entry(
        val name: String,
        val value: String,
        val sensitive: Boolean,
    )

    data class Info(
        val available: Boolean,
        val bootloader: List<Entry>,
        val root: List<Entry>,
        val diagnostics: List<Entry>,
        val error: String? = null,
    ) {
        fun toText(maskSensitive: Boolean): String = buildString {
            append("—— 宿主安全诊断（com.yuewen.nib / native）——\n")
            if (!available) {
                append("宿主诊断结果不可用：")
                append(error ?: "宿主未提供该接口")
                return@buildString
            }
            append("Bootloader 锁状态：")
            append(summarizeBootloader(bootloader.associate { it.name to it.value }))
            append("\nRoot 环境：")
            append(summarizeRoot(root.associate { it.name to it.value }))
            append("\n\n—— Bootloader 原始字段 ——\n")
            appendEntries(bootloader, maskSensitive)
            append("\n\n—— Root 原始字段 ——\n")
            appendEntries(root, maskSensitive)
            append("\n\n—— 其它宿主诊断字段 ——\n")
            appendEntries(diagnostics, maskSensitive)
        }

        private fun StringBuilder.appendEntries(entries: List<Entry>, maskSensitive: Boolean) {
            if (entries.isEmpty()) {
                append("未返回")
                return
            }
            entries.forEachIndexed { index, entry ->
                if (index > 0) append('\n')
                append(entry.name)
                append(": ")
                append(displayValue(entry.value, entry.sensitive && maskSensitive))
            }
        }

        companion object {
            fun unavailable(error: String? = null): Info = Info(
                available = false,
                bootloader = emptyList(),
                root = emptyList(),
                diagnostics = emptyList(),
                error = error,
            )
        }
    }

    fun read(context: Context): Info {
        val out = readRawOut(context)
        val raw = out.raw
        if (raw == null) {
            return out.error?.takeIf(String::isNotBlank)?.let(::unavailable)
                ?: unavailable("宿主 native 未返回诊断 JSON")
        }
        return runCatching {
            parseNormalized(raw, context)
        }.getOrElse { throwable ->
            FeatureHooks.w("Host security diagnostics unavailable: ${throwable.javaClass.name}")
            unavailable(throwable.javaClass.simpleName)
        }
    }

    /**
     * Returns the host's raw native security-collection JSON (decrypted by the host's own `mmr`
     * bridge) as plain text, or null when the host chain is unavailable or the payload is not JSON.
     *
     * Unlike [read] this skips the 64-slot normalization: the raw payload keeps the real JSON keys
     * (e.g. the `IS_MULTI_BOX_*` collection fields), which the Java mapping layer (search.judian)
     * only references through armor-encrypted keys.
     */
    fun readRawJson(context: Context): String? {
        val raw = readRawOut(context).raw ?: return null
        return runCatching {
            val text = String(raw, Charsets.UTF_8)
            JSONObject(text)
            text
        }.getOrNull()
    }

    private data class RawReadout(val raw: ByteArray?, val error: String?)

    private fun readRawOut(context: Context): RawReadout {
        val loader = context.classLoader ?: return RawReadout(null, "宿主 ClassLoader 不可用")
        val armor = findClass(loader, QDReaderHost.Classes.NIB_STRING_ARMOR)
            ?: return RawReadout(null, "宿主未加载 libnib 字符串解码器")
        val wrapper = findClass(loader, QDReaderHost.Classes.NIB_NATIVE_WRAPPER)
            ?: return RawReadout(null, "宿主未加载 libnib native bridge")
        val search = findClass(loader, QDReaderHost.Classes.NIB_SEARCH)
            ?: return RawReadout(null, "宿主未加载 libnib 解析器")
        val subscript = findClass(loader, QDReaderHost.Classes.NIB_SUBSCRIPT)
            ?: return RawReadout(null, "宿主未加载诊断字段枚举")

        val suffixMethod = FeatureHooks.allDeclaredMethods(armor).singleOrNull { method ->
            Modifier.isStatic(method.modifiers) &&
                method.name == "d" &&
                method.parameterTypes.contentEquals(arrayOf(String::class.java)) &&
                method.returnType == String::class.java
        } ?: return RawReadout(null, "找不到宿主字符串解码方法")
        val suffix = FeatureHooks.invoke(suffixMethod, null, NIB_STORAGE_SUFFIX_ARMOR)
            ?.toString()
            ?.takeIf(String::isNotEmpty)
            ?: return RawReadout(null, "无法解析宿主诊断文件路径")

        val mmr = FeatureHooks.allDeclaredMethods(wrapper).singleOrNull { method ->
            Modifier.isStatic(method.modifiers) &&
                method.name == "mmr" &&
                method.parameterTypes.contentEquals(arrayOf(String::class.java)) &&
                method.returnType == ByteArray::class.java
        } ?: return RawReadout(null, "找不到宿主 native 诊断读取方法")
        val raw = FeatureHooks.invoke(
            mmr,
            null,
            context.filesDir.absolutePath + suffix,
        ) as? ByteArray ?: return RawReadout(null, "宿主 native 未返回诊断 JSON")
        return RawReadout(raw, null)
    }

    private fun parseNormalized(raw: ByteArray, context: Context): Info {
        val loader = context.classLoader ?: return unavailable("宿主 ClassLoader 不可用")
        val search = findClass(loader, QDReaderHost.Classes.NIB_SEARCH)
            ?: return unavailable("宿主未加载 libnib 解析器")
        val subscript = findClass(loader, QDReaderHost.Classes.NIB_SUBSCRIPT)
            ?: return unavailable("宿主未加载诊断字段枚举")
        val json = runCatching { JSONObject(String(raw, Charsets.UTF_8)) }
            .getOrElse { return unavailable("宿主诊断 JSON 无法解析") }

        val normalize = FeatureHooks.allDeclaredMethods(search).singleOrNull { method ->
            Modifier.isStatic(method.modifiers) &&
                method.name == "judian" &&
                method.parameterTypes.contentEquals(arrayOf(JSONObject::class.java)) &&
                method.returnType.isArray &&
                method.returnType.componentType == String::class.java
        } ?: return unavailable("找不到宿主诊断字段解析方法")
        val normalized = FeatureHooks.invoke(normalize, null, json) as? Array<*>
            ?: return unavailable("宿主诊断字段解析失败")
        val valuesMethod = FeatureHooks.allDeclaredMethods(subscript).singleOrNull { method ->
            Modifier.isStatic(method.modifiers) &&
                method.name == "values" &&
                method.parameterTypes.isEmpty() &&
                method.returnType.isArray
        } ?: return unavailable("找不到宿主诊断字段枚举")
        val indexMethod = FeatureHooks.allDeclaredMethods(subscript).singleOrNull { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.name == "getIndex" &&
                method.parameterTypes.isEmpty() &&
                method.returnType == Int::class.javaPrimitiveType
        } ?: return unavailable("找不到宿主诊断字段索引方法")
        val enumValues = FeatureHooks.invoke(valuesMethod, null) as? Array<*>
            ?: return unavailable("宿主诊断字段枚举为空")
        val entries = enumValues.mapNotNull { enumValue ->
            val index = (FeatureHooks.invoke(indexMethod, enumValue) as? Number)?.toInt() ?: return@mapNotNull null
            val value = normalized.getOrNull(index)?.toString().orEmpty()
            if (value.isBlank()) return@mapNotNull null
            val name = (enumValue as? Enum<*>)?.name ?: return@mapNotNull null
            Entry(name, value, name in sensitiveNames)
        }
        return Info(
            available = true,
            bootloader = entries.filter { it.name in bootloaderNames },
            root = entries.filter { it.name in rootNames },
            diagnostics = entries.filter { it.name !in bootloaderNames && it.name !in rootNames },
        )
    }

    internal fun summarizeBootloader(values: Map<String, String>): String {
        val direct = values
            .filterKeys { it != "SYS_OEM_UNLOCK_ALLOWED" && it != "SYS_LOCKSETTINGS_REMOTEAUTH_ENABLE" }
            .values
            .map(String::trim)
            .filter(String::isNotEmpty)
        if (direct.isEmpty()) return "未知"
        if (direct.any(::looksUnlocked)) return "已解锁/存在解锁证据"
        if (direct.all(::looksLocked)) return "已锁定"
        return "不确定（原始字段存在漂移）"
    }

    internal fun summarizeRoot(values: Map<String, String>): String {
        val signals = values.values.mapNotNull(::booleanSignal)
        if (signals.any { it }) return "检测到 root 信号"
        if (signals.isNotEmpty() && signals.all { !it }) return "未检测到 root 信号"
        return "未知"
    }

    private fun looksUnlocked(value: String): Boolean {
        val normalized = value.lowercase()
        return normalized.contains("orange") ||
            normalized.contains("unlocked") ||
            normalized.contains("unverified") ||
            normalized == "0" ||
            normalized == "false"
    }

    private fun looksLocked(value: String): Boolean {
        val normalized = value.lowercase()
        return normalized.contains("green") ||
            normalized.contains("locked") ||
            normalized == "1" ||
            normalized == "true"
    }

    private fun booleanSignal(value: String): Boolean? {
        return when (value.trim().lowercase()) {
            "1", "true", "yes", "y", "detected", "hit" -> true
            "0", "false", "no", "n", "clean", "not_detected" -> false
            else -> null
        }
    }

    private fun displayValue(value: String, mask: Boolean): String {
        if (!mask) return value
        val trimmed = value.trim()
        if (trimmed.isBlank()) return "未返回"
        if (trimmed.length <= 4) return "****"
        return trimmed.take(2) + "****" + trimmed.takeLast(2)
    }

    private fun findClass(loader: ClassLoader, name: String): Class<*>? =
        FeatureHooks.findClassOrNull(name, loader)

    private fun unavailable(reason: String): Info = Info.unavailable(reason)
}
