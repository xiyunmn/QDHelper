package com.xiyunmn.qdhelper.feature.welfare.model

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols as QDReaderHost
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Locale

internal fun Method.hasRetrofitPath(pathPart: String): Boolean {
    return annotations.any { annotation ->
        annotation.toString().contains(pathPart, ignoreCase = true)
    }
}

internal fun Any?.flutterMethodName(): String? {
    this ?: return null
    return readObjectField("method")?.toString()
        ?: readNoArgMethod("getMethod")?.toString()
}

internal fun Any?.flutterArgsSummary(methodName: String?): String {
    this ?: return "call=null"
    return when (methodName) {
        "gotoGdtRewardVideo" -> {
            val appId = invokeArgument("appId") as? String
            val posId = invokeArgument("posId") as? String
            "appId=${appId.maskId()}, posId=${posId.maskId()}"
        }
        "getGdtRewardVideoResult" -> {
            val isSec = invokeArgument("isSec")?.toString()?.take(8)
            val msg = invokeArgument("msg")?.toString()
            "isSec=$isSec, msgLen=${msg?.length ?: 0}"
        }
        "askGdtRewardVideoResult" -> "noArgs"
        else -> "method=$methodName"
    }
}

internal fun List<Any?>.finishWatchArgsSummary(): String {
    val safe = mapIndexed { index, value ->
        when (value) {
            is String -> {
                val label = when {
                    looksSensitive(value) -> "sensitive"
                    index == 0 -> "taskId"
                    else -> "str"
                }
                "$index:$label=${value.maskId()}"
            }
            is Number, is Boolean -> "$index:${value.javaClass.simpleName}=$value"
            null -> "$index:null"
            else -> "$index:${value.javaClass.name.substringAfterLast('.')}"
        }
    }
    return "args=[${safe.joinToString()}]"
}

internal fun List<Any?>.finishWatchBusinessArgsSummary(): String {
    return joinToString(prefix = "[", postfix = "]") { value ->
        when (value) {
            is java.util.concurrent.atomic.AtomicInteger -> "AtomicInteger(${value.get()})"
            is java.util.concurrent.atomic.AtomicBoolean -> "AtomicBoolean(${value.get()})"
            is Throwable -> "Throwable(${value.javaClass.name})"
            is android.util.Pair<*, *> -> "Pair(first=${value.first.safeScalarSummary()}, second=${value.second.typeLabel()})"
            null -> "null"
            else -> value.javaClass.name.substringAfterLast('.')
        }
    }
}

internal fun List<Any?>.safeTypeSummary(): String {
    return joinToString(prefix = "[", postfix = "]") { value ->
        value?.javaClass?.name?.substringAfterLast('.') ?: "null"
    }
}

internal fun Intent?.browserIntentSummary(): String {
    this ?: return "intent=null"
    val actionSummary = action?.take(64) ?: "null"
    val componentSummary = component?.className?.let(QDReaderHost::classLabel) ?: "null"
    val categorySummary = categories
        ?.toList()
        ?.sorted()
        ?.joinToString(prefix = "[", postfix = "]") { it.take(48) }
        ?: "[]"
    return "intent={action=$actionSummary, component=$componentSummary, flags=0x${Integer.toHexString(flags)}, " +
        "data=${dataString.sanitizedUrlSummary()}, categories=$categorySummary, extras=${extras.safeExtrasSummary()}}"
}

@Suppress("DEPRECATION")
internal fun Bundle?.safeExtrasSummary(): String {
    this ?: return "none"
    val keys = runCatching { keySet().toList().sorted() }.getOrDefault(emptyList())
    if (keys.isEmpty()) {
        return "empty"
    }
    val shown = keys.take(MAX_EXTRA_KEYS).joinToString(prefix = "[", postfix = "]") { key ->
        val value = runCatching { get(key) }.getOrNull()
        "$key:${value.safeExtraValueSummary(key)}"
    }
    val suffix = if (keys.size > MAX_EXTRA_KEYS) ", omitted=${keys.size - MAX_EXTRA_KEYS}" else ""
    return "count=${keys.size}, values=$shown$suffix"
}

internal fun Any?.safeExtraValueSummary(key: String): String {
    return when (this) {
        null -> "null"
        is String -> {
            val lowerKey = key.lowercase(Locale.US)
            val urlSummary = sanitizedUrlSummary()
            when {
                looksSensitive(lowerKey) -> "String(len=$length,sensitiveKey)"
                urlSummary != "notUrl(len=$length)" -> "String(len=$length,$urlSummary)"
                else -> "String(len=$length)"
            }
        }
        is Number, is Boolean -> toString()
        is Bundle -> "Bundle(${safeExtrasSummary()})"
        is Array<*> -> "Array(size=$size)"
        is Collection<*> -> "${javaClass.name.substringAfterLast('.')}(size=$size)"
        is Map<*, *> -> "Map(keys=${safeKeySummary()})"
        else -> javaClass.name.substringAfterLast('.')
    }
}

internal fun Map<*, *>?.safeKeySummary(): String {
    this ?: return "none"
    if (isEmpty()) {
        return "empty"
    }
    val keys = this.keys
        .map { it?.toString().orEmpty() }
        .filter { it.isNotBlank() }
        .sorted()
    val shown = keys.take(MAX_HEADER_KEYS).joinToString(prefix = "[", postfix = "]") { key ->
        key.take(48)
    }
    val suffix = if (keys.size > MAX_HEADER_KEYS) ", omitted=${keys.size - MAX_HEADER_KEYS}" else ""
    return "count=$size, keys=$shown$suffix"
}

internal fun String?.sanitizedUrlSummary(): String {
    val text = this?.trim().orEmpty()
    if (text.isEmpty()) {
        return "empty"
    }
    val lower = text.lowercase(Locale.US)
    if (lower.startsWith("javascript:")) {
        return "javascript(len=${text.length})"
    }
    val mayBeUrl = "://" in text || lower.startsWith("qdreader:") || lower.startsWith("qd:") || lower.startsWith("http")
    if (!mayBeUrl) {
        return "notUrl(len=${text.length})"
    }
    return runCatching {
        val uri = Uri.parse(text)
        val scheme = uri.scheme.orEmpty().ifBlank { "none" }
        val host = uri.host.orEmpty().ifBlank { "none" }
        val path = uri.path.orEmpty().ifBlank { "/" }.take(MAX_URL_PATH_CHARS)
        val queryKeys = runCatching {
            uri.queryParameterNames
                .toList()
                .sorted()
                .take(MAX_QUERY_KEYS)
        }.getOrDefault(emptyList())
        val hasMoreQueryKeys = runCatching {
            uri.queryParameterNames.size > MAX_QUERY_KEYS
        }.getOrDefault(false)
        val querySummary = if (queryKeys.isEmpty()) {
            "none"
        } else {
            queryKeys.joinToString(prefix = "[", postfix = "]") { key -> key.take(48) } +
                if (hasMoreQueryKeys) ",more" else ""
        }
        "url(scheme=$scheme,host=$host,path=$path,queryKeys=$querySummary,len=${text.length})"
    }.getOrElse {
        "url(parseFailed,len=${text.length})"
    }
}

internal fun String?.isWelfareNetworkUrl(): Boolean {
    val text = this?.trim().orEmpty()
    if (text.isEmpty()) {
        return false
    }
    if (text.isWelfareCenterUrl()) {
        return true
    }
    val lower = text.lowercase(Locale.US)
    if (lower.contains("h5.if.qidian.com/new/welfarecenter")) {
        return true
    }
    if (lower.contains("/argus/api/v2/video/adv/getwelfaretaskreadingpagev2")) {
        return true
    }
    if (lower.contains("/argus/api/v1/video/adv/finishwatch")) {
        return true
    }
    return runCatching {
        val uri = Uri.parse(text)
        val host = uri.host.orEmpty().lowercase(Locale.US)
        val path = uri.path.orEmpty().lowercase(Locale.US)
        host.endsWith("qidian.com") && (
            path == "/new/welfarecenter" ||
                path.contains("/video/adv/getwelfaretaskreadingpagev2") ||
                path.contains("/video/adv/finishwatch") ||
                path.contains("/video/adv/")
            )
    }.getOrDefault(false)
}

internal fun String?.isWelfareCenterUrl(): Boolean {
    val text = this?.trim().orEmpty()
    if (text.isEmpty()) return false
    return runCatching {
        val uri = Uri.parse(text)
        (uri.host.equals("h5.if.qidian.com", ignoreCase = true) ||
            uri.host.equals("brave.if.qidian.com", ignoreCase = true)) &&
            uri.path.orEmpty().trimEnd('/').equals("/new/welfarecenter", ignoreCase = true)
    }.getOrDefault(false)
}

internal fun Any?.safeScalarSummary(): String {
    return when (this) {
        null -> "null"
        is String -> maskId()
        is Number, is Boolean -> toString()
        else -> typeLabel()
    }
}

internal fun Any?.typeLabel(): String {
    return this?.javaClass?.name ?: "null"
}

internal fun Any?.readBooleanField(name: String): Boolean? {
    return readObjectField(name) as? Boolean
}

internal fun Any?.readObjectField(name: String): Any? {
    this ?: return null
    return findField(javaClass, name)?.let { field ->
        FeatureHooks.read(field, this)
    }
}

internal fun Any?.readFieldByType(typeName: String): Any? {
    this ?: return null
    return findFieldByType(javaClass, typeName)?.let { field ->
        FeatureHooks.read(field, this)
    }
}

internal fun Any?.rewardVideoConfigSummary(): String {
    this ?: return "config=null"
    val adPlatform = readNoArgMethod("getAdPlatform") ?: readObjectField("adPlatform")
    val adCodeId = readNoArgMethod("getAdCodeId") as? String
    val adPositionCode = readNoArgMethod("getAdPositionCode") as? String
    val defaultAdPlatform = readNoArgMethod("getDefaultAdPlatform") ?: readObjectField("defaultAdPlatform")
    val defaultAdCodeId = readNoArgMethod("getDefaultAdCodeId") as? String
    return "platform=$adPlatform, adPosition=${adPositionCode.maskId()}, adCodeId=${adCodeId.maskId()}, " +
        "defaultPlatform=$defaultAdPlatform, defaultAdCodeId=${defaultAdCodeId.maskId()}"
}

internal fun Any?.readNoArgMethod(name: String): Any? {
    this ?: return null
    return runCatching {
        val method = FeatureHooks.declaredMethodsNamed(javaClass, name)
            .singleOrNull { candidate -> candidate.parameterTypes.isEmpty() }
            ?: return@runCatching null
        FeatureHooks.invoke(method, this)
    }.getOrNull()
}

internal fun Any.invokeArgument(name: String): Any? {
    return runCatching {
        val method = FeatureHooks.declaredMethodsNamed(javaClass, "argument")
            .singleOrNull { candidate -> candidate.parameterTypes.contentEquals(arrayOf(String::class.java)) }
            ?: return@runCatching null
        FeatureHooks.invoke(method, this, name)
    }.getOrNull()
}

internal fun findField(clazz: Class<*>, name: String): Field? = FeatureHooks.findFieldOrNull(clazz, name)

/**
 * 按字段类型（而非混淆短名）定位唯一字段。混淆只改字段字母，不改声明类型，
 * 因此当目标类里某个类型（如库接口 TangramRewardADListener）只出现一次时，
 * 用类型锚定可跨版本稳定命中。匹配到多个时返回 null，交由调用方决定回退。
 */
internal fun findFieldByType(clazz: Class<*>, typeName: String): Field? {
    return FeatureHooks.allDeclaredFields(clazz).singleOrNull { field ->
        !Modifier.isStatic(field.modifiers) && field.type.name == typeName
    }
}

internal fun findMethod(clazz: Class<*>, name: String, predicate: (Method) -> Boolean): Method? {
    return FeatureHooks.declaredMethodsNamed(clazz, name).singleOrNull(predicate)
}

internal fun Method.ownerLabel(): String {
    return QDReaderHost.classLabel(declaringClass.name)
}

internal fun Method.shortHash(): String {
    return Integer.toHexString(toGenericString().hashCode())
}

internal fun Array<Class<*>>.sizeParams(): String {
    return joinToString(prefix = "(", postfix = ")") { type -> type.simpleName.ifBlank { type.name } }
}

internal fun String?.maskId(): String {
    val text = this?.trim().orEmpty()
    if (text.isEmpty()) {
        return "<empty>"
    }
    if (text.length <= 4) {
        return "len=${text.length}:****"
    }
    return "len=${text.length}:****${text.takeLast(4)}"
}

internal fun Context?.unwrapActivity(): Activity? {
    var current = this
    repeat(12) {
        when (current) {
            is Activity -> return current
            is ContextWrapper -> current = current.baseContext
            else -> return null
        }
    }
    return null
}

internal fun Context?.contextLabel(): String {
    val activity = unwrapActivity()
    if (activity != null) {
        return activity.javaClass.name.substringAfterLast('.')
    }
    return this?.javaClass?.name?.substringAfterLast('.') ?: "null"
}

internal fun Intent?.isLikelyWelfareBrowserIntent(): Boolean {
    this ?: return false
    if (dataString.isWelfareNetworkUrl()) {
        return true
    }
    return extras.containsWelfareUrl()
}

@Suppress("DEPRECATION")
internal fun Bundle?.containsWelfareUrl(): Boolean {
    this ?: return false
    val keys = runCatching { keySet().toList() }.getOrDefault(emptyList())
    return keys.any { key ->
        when (val value = runCatching { get(key) }.getOrNull()) {
            is String -> value.isWelfareNetworkUrl()
            is Bundle -> value.containsWelfareUrl()
            else -> false
        }
    }
}

internal fun looksSensitive(value: String): Boolean {
    val lower = value.lowercase()
    return lower.length >= 16 || SENSITIVE_MARKERS.any { marker -> lower.contains(marker) }
}

internal val WELFARE_FLUTTER_METHODS = setOf(
    "gotoGdtRewardVideo",
    "askGdtRewardVideoResult",
    "getGdtRewardVideoResult",
)

private const val MAX_EXTRA_KEYS = 24
private const val MAX_HEADER_KEYS = 12
private const val MAX_QUERY_KEYS = 24
private const val MAX_URL_PATH_CHARS = 120

private val SENSITIVE_MARKERS = listOf(
    "session",
    "captcha",
    "ticket",
    "challenge",
    "validate",
    "seccode",
    "token",
    "cookie",
)
