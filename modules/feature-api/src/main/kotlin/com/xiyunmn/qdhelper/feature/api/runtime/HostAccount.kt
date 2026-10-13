package com.xiyunmn.qdhelper.feature.api.runtime

import android.content.Context
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import com.xiyunmn.qdhelper.host.api.target.AccountTargetKeys
import com.xiyunmn.qdhelper.host.api.target.HostTargetResolver

/** Positive ID = authenticated account, zero = guest, null = identity unavailable. Never caches the ID. */
fun currentHostUserId(context: Context?, classLoader: ClassLoader): Long? = runCatching {
    val owner = FeatureHooks.findClassOrNull(HostSymbols.Classes.QD_USER_MANAGER, classLoader) ?: return null
    val getter = FeatureHooks.findDeclaredMethodOrNull(owner, "getInstance") ?: return null
    val manager = FeatureHooks.invoke(getter, null) ?: return null
    val loggedIn = HostTargetResolver.resolveMethod(
        context, classLoader, HostSessionStore.processName, AccountTargetKeys.userManagerIsLoggedIn,
    ) ?: return null
    when (FeatureHooks.invoke(loggedIn, manager) as? Boolean) {
        false -> 0L
        null -> null
        true -> {
            val userId = HostTargetResolver.resolveMethod(
                context, classLoader, HostSessionStore.processName, AccountTargetKeys.userManagerUserId,
            ) ?: return null
            (FeatureHooks.invoke(userId, manager) as? Number)?.toLong()?.takeIf { it > 0L }
        }
    }
}.getOrNull()
