package com.xiyunmn.qdhelper.ui

import android.content.Context
import android.graphics.Color
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.host.api.target.HostTargetResolver
import com.xiyunmn.qdhelper.host.api.target.UiThemeTargetKeys
import com.xiyunmn.qdhelper.shared.ui.HostThemeResolver
import com.xiyunmn.qdhelper.shared.ui.UiColors

internal object HostUiThemeResolver : HostThemeResolver {
    private val roleMethods = mapOf(
        "surface" to "getBookShelfSurface01",
        "text" to "getBookShelfFont",
        "muted" to "getBookShelfFontLight",
        "onSurface" to "getBookShelfFontOnSurface",
        "brand" to "getBookShelfFontHLight",
        "divider" to "getBookShelfBorder01",
    )

    fun install() {
        UiColors.hostThemeResolver = this
    }

    override fun isNightMode(context: Context): Boolean? = runCatching {
        val method = HostTargetResolver.resolveMethod(
            context = context,
            classLoader = context.classLoader,
            processName = HostSessionStore.processName,
            key = UiThemeTargetKeys.nightMode,
        ) ?: return@runCatching null
        FeatureHooks.invoke(method, null) as? Boolean
    }.getOrNull()

    override fun color(context: Context, role: String): Int? {
        val methodName = roleMethods[role] ?: return null
        val raw = runCatching {
            Class.forName(
                "com.qidian.QDReader.component.config.QDAppConfigHelper",
                false,
                context.classLoader,
            ).getMethod(methodName).invoke(null) as? String
        }.getOrNull()
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val normalized = when {
            value.startsWith("#") -> value
            value.startsWith("0x", ignoreCase = true) -> "#${value.substring(2)}"
            value.matches(Regex("[0-9a-fA-F]{6}|[0-9a-fA-F]{8}")) -> "#$value"
            else -> return null
        }
        return runCatching { Color.parseColor(normalized) }.getOrNull()
    }
}
