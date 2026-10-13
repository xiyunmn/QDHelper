package com.xiyunmn.qdhelper.feature.welfare.host

import android.view.View
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.host.api.reflection.HostAccess
import com.xiyunmn.qdhelper.host.api.target.HostTargetResolver
import com.xiyunmn.qdhelper.host.api.target.TargetKey
import com.xiyunmn.qdhelper.host.api.target.WelfareTargetKeys
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/** The host's X5 WebView may use either Chromium or X5; never depend on its child implementation. */
internal class WelfareWebViewHost(private val methods: Map<TargetKey.MethodTarget, Method>) {
    fun webView(fragment: Any?): View? = fragment?.let {
        (FeatureHooks.invokeResult(methods.getValue(WelfareTargetKeys.browserWebView), it) as? HostAccess.Value)?.value as? View
    }
    fun isWebView(view: View): Boolean = methods.getValue(WelfareTargetKeys.webViewUrl).declaringClass.isInstance(view)
    fun url(view: View): String? =
        (FeatureHooks.invokeResult(methods.getValue(WelfareTargetKeys.webViewUrl), view) as? HostAccess.Value)?.value as? String

    fun evaluate(view: View, script: String, callback: (String?) -> Unit): Boolean {
        val method = methods.getValue(WelfareTargetKeys.webViewEvaluate)
        val callbackType = method.parameterTypes[1]
        val proxy = Proxy.newProxyInstance(callbackType.classLoader, arrayOf(callbackType)) { instance, member, args ->
            when (member.name) {
                "onReceiveValue" -> { callback(args?.firstOrNull() as? String); null }
                "equals" -> instance === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(instance)
                "toString" -> "WelfarePageReceipt"
                else -> null
            }
        }
        return FeatureHooks.invokeResult(method, view, script, proxy) is HostAccess.Value
    }

    companion object {
        fun resolve(classLoader: ClassLoader): WelfareWebViewHost? {
            val keys = setOf(WelfareTargetKeys.browserWebView, WelfareTargetKeys.webViewUrl, WelfareTargetKeys.webViewEvaluate)
            val methods = keys.mapNotNull { key ->
                HostTargetResolver.resolveMethod(HostSessionStore.contextOrNull(), classLoader, HostSessionStore.processName, key)
                    ?.let { key to it }
            }.toMap()
            if (methods.size != keys.size) {
                FeatureHooks.w("Welfare popup WebView unavailable: resolved=${methods.size}/${keys.size}")
                return null
            }
            return WelfareWebViewHost(methods)
        }
    }
}
