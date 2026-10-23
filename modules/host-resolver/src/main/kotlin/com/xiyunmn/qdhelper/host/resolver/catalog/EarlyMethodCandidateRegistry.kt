package com.xiyunmn.qdhelper.host.resolver.catalog

import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import com.xiyunmn.qdhelper.host.api.target.DialogTargetKeys
import com.xiyunmn.qdhelper.host.api.target.StartupTargetKeys
import com.xiyunmn.qdhelper.host.api.target.TargetKey
import com.xiyunmn.qdhelper.host.resolver.reflection.ReflectionAccess
import com.xiyunmn.qdhelper.host.resolver.dexkit.query.hasAutoUpdateJudianShape
import java.lang.reflect.Method

/** Minimal audited owner anchors for hooks that can run before a host Context exists. */
internal object EarlyMethodCandidateRegistry {
    private val ownersByTarget = mapOf(
        StartupTargetKeys.splashSkipAd.id to listOf(HostSymbols.Classes.SPLASH_MANAGER),
        StartupTargetKeys.splashDownloadCacheDir.id to listOf(HostSymbols.Classes.SPLASH_DOWNLOAD_UTIL),
        StartupTargetKeys.splashActivityInitCustom.id to listOf(HostSymbols.Classes.SPLASH_ACTIVITY),
        StartupTargetKeys.splashActivityInitDefault.id to listOf(HostSymbols.Classes.SPLASH_ACTIVITY),
        StartupTargetKeys.splashConfigButtonBind.id to listOf(HostSymbols.Classes.SPLASH_CONFIG_FRAGMENT_KT),
    )

    val targetIds = ownersByTarget.keys + setOf(DialogTargetKeys.autoUpdateEntry.id, DialogTargetKeys.updatePresenter.id)

    fun candidates(classLoader: ClassLoader, key: TargetKey.MethodTarget): List<Method> {
        if (key == DialogTargetKeys.autoUpdateEntry || key == DialogTargetKeys.updatePresenter) {
            val presenters = listOf(HostSymbols.Classes.MAIN_GROUP_ACTIVITY, HostSymbols.Classes.ABOUT_ACTIVITY)
                .mapNotNull { ReflectionAccess.findClass(classLoader, it) }
                .flatMap { ReflectionAccess.fieldsDeclaredBy(it).map { field -> field.type } }
                .distinct()
                .filter { type -> type.interfaces.any { it.enclosingClass?.let(::hasAutoUpdateJudianShape) == true } }
            val owners = if (key == DialogTargetKeys.updatePresenter) presenters else presenters
                .flatMap { it.interfaces.mapNotNull { callback -> callback.enclosingClass } }
                .filter(::hasAutoUpdateJudianShape).distinct()
            return owners.flatMap { ReflectionAccess.methodsDeclaredBy(it).toList() }
        }
        return ownersByTarget[key.id].orEmpty()
            .asSequence()
            .mapNotNull { ownerName -> ReflectionAccess.findClass(classLoader, ownerName) }
            .flatMap { owner ->
                runCatching { ReflectionAccess.methodsDeclaredBy(owner).asSequence() }
                    .getOrDefault(emptySequence())
            }
            .toList()
    }
}
