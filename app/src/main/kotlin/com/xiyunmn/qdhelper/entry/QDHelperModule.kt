package com.xiyunmn.qdhelper.entry

import com.xiyunmn.qdhelper.runtime.ModuleCoordinator
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

class QDHelperModule : XposedModule() {
    private val coordinator = ModuleCoordinator(this)

    override fun onModuleLoaded(param: ModuleLoadedParam) = coordinator.onModuleLoaded(param)

    override fun onPackageLoaded(param: PackageLoadedParam) = coordinator.onPackageLoaded(param)

    override fun onPackageReady(param: PackageReadyParam) = coordinator.onPackageReady(param)

    override fun onHotReloading(param: HotReloadingParam): Boolean = coordinator.onHotReloading(param)

    override fun onHotReloaded(param: HotReloadedParam) = coordinator.onHotReloaded(param)
}
