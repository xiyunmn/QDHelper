package com.xiyunmn.qdhelper.feature.diagnostics.cookie

import android.content.Context
import android.provider.Settings
import android.webkit.WebSettings
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.host.api.target.HostTargetResolver
import com.xiyunmn.qdhelper.host.api.target.AccountTargetKeys
import com.xiyunmn.qdhelper.host.api.target.DiagnosticsTargetKeys
import com.xiyunmn.qdhelper.host.api.target.NetworkIdentityTargetKeys
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols as QDReaderHost
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.config.SettingsRuntime
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Local-only debug readout of host account + device identity signals.
 * Used by the settings "Cookie / 身份" dialog; never uploads.
 */
object CookieDebugInfo {
    private const val QIDIAN_DOMAIN = ".qidian.com"

    fun read(context: Context): Info {
        return Info(
            userId = readUserId(context),
            userAgent = readUserAgent(context),
            cookie = readCookie(context),
            androidId = readAndroidId(context),
            qimei16 = readResolvedStaticString(
                context,
                NetworkIdentityTargetKeys.commonDeviceQimei16,
            ),
            qimei36 = readResolvedStaticString(
                context,
                NetworkIdentityTargetKeys.commonDeviceQimei36,
            ),
            oaid = readResolvedStaticString(context, DiagnosticsTargetKeys.hostOaidGetter),
            ywGuid = readQdConfig(context, "SettingYWGuid"),
            ywKey = readQdConfig(context, "SettingYWKey"),
            isFirstLaunch = readFirstLaunch(context),
            isFreshman = readCompanionBoolean(context, "getFreshMan"),
            isLimitFree = readCompanionBoolean(context, "isNewUserFreeReading"),
            showLimitFreePopup = readCompanionBoolean(context, "getShowLimitFreePopup"),
            hostSecurity = HostSecurityDiagnostics.read(context),
            tianyu = TianyuDiagnostics.read(context),
            hostRawJson = if (runCatching { SettingsRuntime.isHostCollectJsonEnabled(context) }.getOrDefault(false)) {
                runCatching { HostSecurityDiagnostics.readRawJson(context) }.getOrNull()
            } else {
                null
            },
        )
    }

    private fun readUserId(context: Context): String {
        return runCatching {
            val clazz = loadClass(context, QDReaderHost.Classes.QD_USER_MANAGER)
                ?: return@runCatching ""
            val instanceMethod = FeatureHooks.findDeclaredMethodOrNull(clazz, "getInstance")
                ?: return@runCatching ""
            val instance = FeatureHooks.invoke(instanceMethod, null) ?: return@runCatching ""
            val userIdMethod = HostTargetResolver.resolveMethod(
                context = context,
                classLoader = context.classLoader ?: return@runCatching "",
                processName = HostSessionStore.processName,
                key = AccountTargetKeys.userManagerUserId,
            )
                ?: return@runCatching ""
            FeatureHooks.invoke(userIdMethod, instance)?.toString().orEmpty()
        }.getOrDefault("")
    }

    private fun readUserAgent(context: Context): String {
        readX5UserAgent(context).takeIf { it.isNotBlank() }?.let { return it }
        return runCatching { WebSettings.getDefaultUserAgent(context) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: System.getProperty("http.agent").orEmpty()
    }

    private fun readX5UserAgent(context: Context): String {
        return runCatching {
            val clazz = loadClass(context, QDReaderHost.Classes.X5_WEB_SETTINGS) ?: return@runCatching ""
            val method = FeatureHooks.findDeclaredMethodOrNull(clazz, "getDefaultUserAgent", Context::class.java)
                ?: return@runCatching ""
            FeatureHooks.invoke(method, null, context)
                ?.toString()
                .orEmpty()
        }.getOrDefault("")
    }

    private fun readCookie(context: Context): String {
        readX5Cookie(context).takeIf { it.isNotBlank() }?.let { return it }
        readSystemCookie().takeIf { it.isNotBlank() }?.let { return it }
        return readHostCookieString(context)
    }

    private fun readX5Cookie(context: Context): String {
        return runCatching {
            val clazz = loadClass(context, QDReaderHost.Classes.X5_COOKIE_MANAGER) ?: return@runCatching ""
            val instanceMethod = FeatureHooks.findDeclaredMethodOrNull(clazz, "getInstance")
                ?: return@runCatching ""
            val instance = FeatureHooks.invoke(instanceMethod, null) ?: return@runCatching ""
            val getCookie = FeatureHooks.findDeclaredMethodOrNull(clazz, "getCookie", String::class.java)
                ?: return@runCatching ""
            FeatureHooks.invoke(getCookie, instance, QIDIAN_DOMAIN)
                ?.toString()
                .orEmpty()
        }.getOrDefault("")
    }

    private fun readSystemCookie(): String {
        return runCatching {
            android.webkit.CookieManager.getInstance()
                .getCookie(QIDIAN_DOMAIN)
                .orEmpty()
        }.getOrDefault("")
    }

    private fun readHostCookieString(context: Context): String {
        // Locate the cookie getter through its resolver contract, then derive the declaring class
        // and its static singleton getter from that verified hit.
        val getter = resolveHostCookieGetter(context) ?: return ""
        return runCatching {
            val clazz = getter.declaringClass
            val singletonGetter = FeatureHooks.allDeclaredMethods(clazz).singleOrNull { candidate ->
                Modifier.isStatic(candidate.modifiers) &&
                    candidate.parameterTypes.isEmpty() &&
                    candidate.returnType == clazz
            } ?: return@runCatching ""
            val instance = FeatureHooks.invoke(singletonGetter, null) ?: return@runCatching ""
            FeatureHooks.invoke(getter, instance)?.toString().orEmpty()
        }.getOrDefault("")
    }

    internal fun prepareHostCookieGetter(context: Context) {
        resolveHostCookieGetter(context)
        resolveIdentityTargets(context)
    }

    private fun resolveIdentityTargets(context: Context) {
        readResolvedStaticString(context, NetworkIdentityTargetKeys.commonDeviceQimei16)
        readResolvedStaticString(context, NetworkIdentityTargetKeys.commonDeviceQimei36)
        readResolvedStaticString(context, DiagnosticsTargetKeys.hostOaidGetter)
    }

    private fun readResolvedStaticString(
        context: Context,
        key: com.xiyunmn.qdhelper.host.api.target.TargetKey.MethodTarget,
    ): String {
        val method = resolveHostStringGetter(context, key) ?: return ""
        return runCatching { FeatureHooks.invoke(method, null) as? String }.getOrNull().orEmpty()
    }

    private fun resolveHostStringGetter(
        context: Context,
        key: com.xiyunmn.qdhelper.host.api.target.TargetKey.MethodTarget,
    ): Method? {
        val classLoader = context.classLoader ?: return null
        return HostTargetResolver.resolveMethod(
            context = context,
            classLoader = classLoader,
            processName = HostSessionStore.processName,
            key = key,
        )?.takeIf { method ->
            method.declaringClass.name == QDReaderHost.Classes.COMMON_LIB_DEVICE &&
                Modifier.isStatic(method.modifiers) &&
                method.parameterTypes.isEmpty() &&
                method.returnType == String::class.java
        }
    }

    private fun resolveHostCookieGetter(context: Context): Method? {
        val classLoader = context.classLoader ?: return null
        return HostTargetResolver.resolveMethod(
            context = context,
            classLoader = classLoader,
            processName = HostSessionStore.processName,
            key = DiagnosticsTargetKeys.hostCookieGetter,
        )
    }

    private fun readAndroidId(context: Context): String {
        return runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        }.getOrDefault("")
    }

    private fun readQdConfig(context: Context, key: String): String {
        return runCatching {
            val clazz = loadClass(context, QDReaderHost.Classes.QD_CONFIG) ?: return@runCatching ""
            val instanceMethod = FeatureHooks.findDeclaredMethodOrNull(clazz, "getInstance")
                ?: return@runCatching ""
            val instance = FeatureHooks.invoke(instanceMethod, null) ?: return@runCatching ""
            val getSetting = FeatureHooks.findDeclaredMethodOrNull(
                clazz,
                "GetSetting",
                String::class.java,
                String::class.java,
            ) ?: return@runCatching ""
            FeatureHooks.invoke(getSetting, instance, key, "")
                ?.toString()
                .orEmpty()
        }.getOrDefault("")
    }

    private fun readFirstLaunch(context: Context): String {
        return runCatching {
            val clazz = loadClass(context, QDReaderHost.Classes.APPLICATION_CONTEXT) ?: return@runCatching ""
            val method = FeatureHooks.findDeclaredMethodOrNull(clazz, "isFirstLaunch")
                ?: return@runCatching ""
            FeatureHooks.invoke(method, null)?.toString().orEmpty()
        }.getOrDefault("")
    }

    private fun readCompanionBoolean(context: Context, methodName: String): String {
        return runCatching {
            val companion = loadClass(
                context,
                QDReaderHost.Classes.QD_APP_CONFIG_HELPER_COMPANION,
            ) ?: return@runCatching ""
            val method = FeatureHooks.declaredMethodsNamed(companion, methodName).singleOrNull {
                it.name == methodName && it.parameterTypes.isEmpty()
            } ?: return@runCatching ""
            val instance = FeatureHooks.allDeclaredFields(companion).singleOrNull {
                Modifier.isStatic(it.modifiers) && it.type == companion
            }?.let { field -> FeatureHooks.read(field, null) }
            val value = FeatureHooks.invoke(method, instance)
            value?.toString().orEmpty()
        }.getOrDefault("")
    }

    private fun loadClass(context: Context, name: String): Class<*>? {
        val loaders = listOfNotNull(
            context.classLoader,
            Thread.currentThread().contextClassLoader,
            CookieDebugInfo::class.java.classLoader,
        )
        return loaders.firstNotNullOfOrNull { loader -> FeatureHooks.findClassOrNull(name, loader) }
    }

    data class Info(
        val userId: String,
        val userAgent: String,
        val cookie: String,
        val androidId: String = "",
        val qimei16: String = "",
        val qimei36: String = "",
        val oaid: String = "",
        val ywGuid: String = "",
        val ywKey: String = "",
        val isFirstLaunch: String = "",
        val isFreshman: String = "",
        val isLimitFree: String = "",
        val showLimitFreePopup: String = "",
        val hostSecurity: HostSecurityDiagnostics.Info = HostSecurityDiagnostics.Info.unavailable(),
        val tianyu: TianyuDiagnostics.Info = TianyuDiagnostics.Info.unavailable(),
        val hostRawJson: String? = null,
    ) {
        fun toDisplayText(): String {
            return toText(maskSensitive = true)
        }

        fun toFullText(): String {
            return toText(maskSensitive = false)
        }

        private fun toText(maskSensitive: Boolean): String {
            val displayUserId = if (maskSensitive) userId.maskValue() else userId
            val displayCookie = if (maskSensitive) cookie.maskCookie() else cookie
            val displayAid = if (maskSensitive) androidId.maskValue() else androidId
            val displayQ16 = if (maskSensitive) qimei16.maskValue() else qimei16
            val displayQ36 = if (maskSensitive) qimei36.maskValue() else qimei36
            val displayOaid = if (maskSensitive) oaid.maskValue() else oaid
            val displayGuid = if (maskSensitive) ywGuid.maskValue() else ywGuid
            val displayKey = if (maskSensitive) ywKey.maskValue() else ywKey
            return buildString {
                append("—— 账号 ——\n")
                append("User-ID:\n")
                append(displayUserId.ifBlank { "未读取到" })
                append("\n\nYWGuid:\n")
                append(displayGuid.ifBlank { "未读取到" })
                append("\n\nYWKey:\n")
                append(displayKey.ifBlank { "未读取到" })
                append("\n\nUser-Agent:\n")
                append(userAgent.ifBlank { "未读取到" })
                append("\n\nCookie:\n")
                append(displayCookie.ifBlank { "未读取到" })
                append("\n\n—— 设备身份（宿主采集相关）——\n")
                append("AndroidId(SSAID):\n")
                append(displayAid.ifBlank { "未读取到" })
                append("\n\nQIMEI16:\n")
                append(displayQ16.ifBlank { "未读取到/空" })
                append("\n\nQIMEI36:\n")
                append(displayQ36.ifBlank { "未读取到/空" })
                append("\n\nOAID:\n")
                append(displayOaid.ifBlank { "未读取到/空" })
                append("\n\n—— 服务端配置消费 ——\n")
                append("isFirstLaunch: ")
                append(isFirstLaunch.ifBlank { "?" })
                append("\nisFreshman: ")
                append(isFreshman.ifBlank { "?" })
                append("\nisLimitFree(IsFreshmanLimitFree): ")
                append(isLimitFree.ifBlank { "?" })
                append("\nshowLimitFreePopup: ")
                append(showLimitFreePopup.ifBlank { "?" })
                append("\n\n")
                append(hostSecurity.toText(maskSensitive))
                append("\n\n")
                append(tianyu.toText(maskSensitive))
                if (!maskSensitive) appendRawJson()
            }
        }

        private fun StringBuilder.appendRawJson() {
            append("\n\n—— 宿主采集原始 JSON（libnib mmr 明文，含检测字段键名与原始值）——\n")
            if (hostRawJson.isNullOrBlank()) {
                append("不可用（宿主链不可用或非 JSON 载荷）")
                return
            }
            append(hostRawJson)
        }

        private fun String.maskValue(): String {
            val value = trim()
            if (value.isBlank()) return ""
            if (value.length <= 4) return "****"
            return value.take(2) + "****" + value.takeLast(2)
        }

        private fun String.maskCookie(): String {
            return split(';')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString("; ") { item ->
                    val index = item.indexOf('=')
                    if (index <= 0) {
                        "<redacted>"
                    } else {
                        item.substring(0, index + 1) + "<redacted>"
                    }
                }
        }
    }
}
