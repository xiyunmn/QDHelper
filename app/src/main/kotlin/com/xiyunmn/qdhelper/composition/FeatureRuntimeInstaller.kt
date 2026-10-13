package com.xiyunmn.qdhelper.composition

import com.xiyunmn.qdhelper.hook.runtime.HookRuntime
import com.xiyunmn.qdhelper.capability.originalimage.OriginalImageLongPressBinder
import com.xiyunmn.qdhelper.host.resolver.prewarm.TargetPrewarmPlanner
import com.xiyunmn.qdhelper.host.api.target.TargetId
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.runtime.RuntimeCallbackBridges
import com.xiyunmn.qdhelper.runtime.ViewLongPressCallbacks
import com.xiyunmn.qdhelper.runtime.HookModuleAdapter
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import com.xiyunmn.qdhelper.ui.HostUiThemeResolver
import io.github.libxposed.api.XposedModule

object FeatureRuntimeInstaller {
    fun installPackageReady(module: XposedModule, classLoader: ClassLoader, processName: String) {
        installSelected(
            module,
            classLoader,
            processName,
            phase = "packageReady",
            failureLabel = "Feature install failed",
            hotReload = false,
        )
    }

    fun installHotReloaded(module: XposedModule, classLoader: ClassLoader, processName: String) {
        installSelected(
            module,
            classLoader,
            processName,
            phase = "hotReloaded",
            failureLabel = "Feature hot reload failed",
            hotReload = true,
        )
    }

    private fun installSelected(
        module: XposedModule,
        classLoader: ClassLoader,
        processName: String,
        phase: String,
        failureLabel: String,
        hotReload: Boolean,
    ) {
        RuntimeCallbackBridges.install(module)
        FeatureRuntimeComposition.configure()
        SettingsComposition.configure(processName, HostSessionStore::contextOrNull)
        OriginalImageLongPressBinder.configure(ViewLongPressCallbacks, HookRuntime::e)
        HostUiThemeResolver.install()
        val context = HostSessionStore.contextOrNull()
        val features = FeatureCatalogComposition.selected(context, hotReload)
        val selectedCount = FeatureCatalogComposition.selectedCountBeforeHotReloadFilter(context)
        ResolverFeatureRetryCoordinator.configure(processName) { targetIds ->
            retryResolvedTargets(module, classLoader, processName, targetIds)
        }
        HookRuntime.i(
            "Feature install queue: phase=$phase, process=$processName, count=${features.size}, " +
                "skipped=${selectedCount - features.size}, names=${features.joinToString(">") { it.feature.name }}",
        )
        TargetPrewarmPlanner.schedule(
            context,
            classLoader,
            processName,
            "feature-install:$phase",
            activeTargetOwners(context, features.map { it.id }),
        )
        val hookModule = HookModuleAdapter(module)
        features.forEach { provider ->
            installOne(provider, hookModule, classLoader, processName, phase, failureLabel)
        }
    }

    fun retryApplicationReady(
        module: XposedModule,
        classLoader: ClassLoader,
        processName: String,
        reason: String,
    ) {
        val phase = "applicationReady:$reason"
        val features = FeatureCatalogComposition.selected(HostSessionStore.contextOrNull(), hotReload = false)
        HookRuntime.i(
            "Feature install queue: phase=$phase, process=$processName, count=${features.size}, " +
                "names=${features.joinToString(">") { it.feature.name }}",
        )
        val context = HostSessionStore.contextOrNull()
        TargetPrewarmPlanner.schedule(
            context,
            classLoader,
            processName,
            "feature-retry:$phase",
            activeTargetOwners(context, features.map { it.id }),
        )
        FeatureRuntimeComposition.configure()
        SettingsComposition.configure(processName, HostSessionStore::contextOrNull)
        val hookModule = HookModuleAdapter(module)
        features.forEach { provider ->
            installOne(provider, hookModule, classLoader, processName, phase, "Feature retry failed")
        }
    }

    private fun installOne(
        provider: ConfiguredFeatureProvider,
        module: HookModule,
        classLoader: ClassLoader,
        processName: String,
        phase: String,
        failureLabel: String,
    ) {
        val feature = provider.feature
        val startMs = System.currentTimeMillis()
        HookRuntime.i(
            "Feature install start: id=${provider.id}, ${feature.name}, " +
                "phase=$phase, process=$processName",
        )
        runCatching {
            feature.install(module, classLoader, processName, phase = phase)
        }.onSuccess {
            HookRuntime.i(
                "Feature install done: id=${provider.id}, ${feature.name}, phase=$phase, " +
                    "elapsedMs=${System.currentTimeMillis() - startMs}",
            )
        }.onFailure { throwable ->
            HookRuntime.e("$failureLabel: id=${provider.id}, ${feature.name}", throwable)
        }
    }

    private fun retryResolvedTargets(
        module: XposedModule,
        classLoader: ClassLoader,
        processName: String,
        targetIds: Set<TargetId>,
    ) {
        val providers = FeatureCatalogComposition.selectedForResolvedTargets(
            context = HostSessionStore.contextOrNull(),
            targetIds = targetIds,
        )
        if (providers.isEmpty()) return
        val phase = "resolverReady:${targetIds.map { it.value }.sorted().joinToString(",")}"
        HookRuntime.i(
            "Feature targeted retry: phase=$phase, process=$processName, " +
                "features=${providers.joinToString(">") { it.id }}",
        )
        val hookModule = HookModuleAdapter(module)
        providers.forEach { provider ->
            installOne(provider, hookModule, classLoader, processName, phase, "Feature resolver retry failed")
        }
    }

}
