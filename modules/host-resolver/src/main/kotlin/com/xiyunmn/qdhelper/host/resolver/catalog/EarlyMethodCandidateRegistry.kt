package com.xiyunmn.qdhelper.host.resolver.catalog

import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import com.xiyunmn.qdhelper.host.api.target.DialogTargetKeys
import com.xiyunmn.qdhelper.host.api.target.StartupTargetKeys
import com.xiyunmn.qdhelper.host.api.target.TargetKey
import com.xiyunmn.qdhelper.host.resolver.reflection.ReflectionAccess
import java.lang.reflect.Method

/** Minimal audited owner anchors for hooks that can run before a host Context exists. */
internal object EarlyMethodCandidateRegistry {
    private val ownersByTarget = mapOf(
        StartupTargetKeys.splashSkipAd.id to listOf(HostSymbols.Classes.SPLASH_MANAGER),
        StartupTargetKeys.splashDownloadCacheDir.id to listOf(HostSymbols.Classes.SPLASH_DOWNLOAD_UTIL),
        StartupTargetKeys.splashActivityInitCustom.id to listOf(HostSymbols.Classes.SPLASH_ACTIVITY),
        StartupTargetKeys.splashActivityInitDefault.id to listOf(HostSymbols.Classes.SPLASH_ACTIVITY),
        StartupTargetKeys.splashConfigButtonBind.id to listOf(HostSymbols.Classes.SPLASH_CONFIG_FRAGMENT_KT),
        DialogTargetKeys.autoUpdateEntry.id to listOf(
            "b6.g", // 7.9.469-7.9.474
            "d6.g", // 7.9.476
        ),
    )

    val targetIds = ownersByTarget.keys

    fun candidates(classLoader: ClassLoader, key: TargetKey.MethodTarget): List<Method> =
        ownersByTarget[key.id].orEmpty()
            .asSequence()
            .mapNotNull { ownerName -> ReflectionAccess.findClass(classLoader, ownerName) }
            .flatMap { owner ->
                runCatching { ReflectionAccess.methodsDeclaredBy(owner).asSequence() }
                    .getOrDefault(emptySequence())
            }
            .toList()
}
