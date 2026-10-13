package com.xiyunmn.qdhelper.feature.welfare.host

import android.app.Activity
import android.content.Context
import android.view.View
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.welfare.model.WelfareClaimOutcome
import com.xiyunmn.qdhelper.feature.welfare.model.unwrapActivity
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.host.api.reflection.HostAccess
import com.xiyunmn.qdhelper.host.api.target.AccountTargetKeys
import com.xiyunmn.qdhelper.host.api.target.HostTargetResolver
import com.xiyunmn.qdhelper.host.api.target.TargetKey
import com.xiyunmn.qdhelper.host.api.target.WelfareTargetKeys
import java.lang.reflect.Method

internal class WelfareRecoveryHost private constructor(private val methods: Map<TargetKey.MethodTarget, Method>) {
    private val web = WelfareWebViewHost(methods)
    operator fun get(key: TargetKey.MethodTarget): Method = methods.getValue(key)

    fun value(key: TargetKey.MethodTarget, receiver: Any?, vararg args: Any?): Any? =
        (FeatureHooks.invokeResult(get(key), receiver, *args) as? HostAccess.Value)?.value

    fun webView(fragment: Any?): View? = web.webView(fragment)
    fun isWebView(view: View): Boolean = web.isWebView(view)
    fun url(view: View): String? = web.url(view)

    fun groupedActivity(dialog: Any?): Activity? {
        if (dialog == null || !get(WelfareTargetKeys.groupedClaimEvent).parameterTypes[0].isInstance(dialog)) return null
        // The host loading dialog is a framework wrapper with mContext, not android.app.Dialog.
        return (FeatureHooks.readDeclaredFieldValue(dialog, "mContext") as? Context)?.unwrapActivity()
    }

    /** The identifier stays in memory solely to invalidate actions after an account change. */
    fun account(): String? {
        val manager = value(WelfareTargetKeys.userManagerInstance, null) ?: return null
        return value(AccountTargetKeys.userManagerUserId, manager)?.toString()?.takeIf { it.isNotBlank() }
    }

    fun outcome(response: Any?): WelfareClaimOutcome {
        if (response == null || !get(WelfareTargetKeys.responseSuccess).declaringClass.isInstance(response)) {
            return WelfareClaimOutcome.UNKNOWN
        }
        val success = FeatureHooks.invokeResult(get(WelfareTargetKeys.responseSuccess), response)
        if (success !is HostAccess.Value || success.value !is Boolean) return WelfareClaimOutcome.UNKNOWN
        if (success.value == false) return WelfareClaimOutcome.BUSINESS_REJECTED
        val data = value(WelfareTargetKeys.responseData, response) ?: return WelfareClaimOutcome.UNKNOWN
        if (!get(WelfareTargetKeys.rewardRisk).declaringClass.isInstance(data)) return WelfareClaimOutcome.UNKNOWN
        val risk = FeatureHooks.invokeResult(get(WelfareTargetKeys.rewardRisk), data)
        if (risk !is HostAccess.Value) return WelfareClaimOutcome.UNKNOWN
        if (risk.value != null) return WelfareClaimOutcome.RISK_REJECTED
        val rewards = value(WelfareTargetKeys.rewardList, data) as? List<*> ?: return WelfareClaimOutcome.UNKNOWN
        return if (rewards.isEmpty()) WelfareClaimOutcome.EMPTY else WelfareClaimOutcome.REWARDED
    }

    fun evaluate(view: View, script: String, callback: (String?) -> Unit): Boolean = web.evaluate(view, script, callback)

    companion object {
        fun resolve(classLoader: ClassLoader): WelfareRecoveryHost? {
            val keys = WelfareTargetKeys.recovery + AccountTargetKeys.userManagerUserId
            val resolved = keys.mapNotNull { key ->
                HostTargetResolver.resolveMethod(
                    HostSessionStore.contextOrNull(), classLoader, HostSessionStore.processName, key,
                )?.let { key to it }
            }.toMap()
            if (resolved.size != keys.size) {
                FeatureHooks.w("Welfare recovery unavailable: resolved=${resolved.size}/${keys.size}")
                return null
            }
            return WelfareRecoveryHost(resolved)
        }
    }
}
