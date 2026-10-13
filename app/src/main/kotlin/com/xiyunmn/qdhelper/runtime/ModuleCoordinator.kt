package com.xiyunmn.qdhelper.runtime

import android.app.Application
import android.os.Bundle
import com.xiyunmn.qdhelper.foundation.android.AndroidApiSupport
import com.xiyunmn.qdhelper.hook.resource.RuntimeCleanupRegistry
import com.xiyunmn.qdhelper.hook.runtime.HookRuntime
import com.xiyunmn.qdhelper.composition.FeatureRuntimeInstaller
import com.xiyunmn.qdhelper.runtime.HostLifecycleProbe
import com.xiyunmn.qdhelper.hook.framework.ApplicationLifecycleBridge
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols as QDReaderHost
import com.xiyunmn.qdhelper.host.resolver.environment.ResolverEnvironment
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.util.concurrent.atomic.AtomicBoolean

class ModuleCoordinator(
    private val module: XposedModule,
) {
    @Volatile
    private var runtimeEnabled = false
    private var processName: String = ""
    private var targetClassLoader: ClassLoader? = null
    private var targetPackageReady: Boolean = false
    private val applicationReadyRetryDone = AtomicBoolean(false)
    private val unverifiedApiWarningLogged = AtomicBoolean(false)

    fun onModuleLoaded(param: ModuleLoadedParam) {
        if (!enableForCurrentApi()) return
        com.xiyunmn.qdhelper.shared.compose.ModuleComposeEnvironment.configure(module.moduleApplicationInfo)
        HookRuntime.attach(module)
        attachResolverEnvironment()
        processName = param.processName
        module.log(ANDROID_LOG_INFO, TAG, "Module loaded: process=$processName, api=${module.apiVersion}")
    }

    fun onPackageLoaded(param: PackageLoadedParam) {
        if (!runtimeEnabled) return
        if (!QDReaderHost.isTargetPackage(param.packageName)) return
        HookRuntime.i(
            "Target package loaded: package=${param.packageName}, " +
                "process=${QDReaderHost.processLabel(processName)}, first=${param.isFirstPackage}",
        )
    }

    fun onPackageReady(param: PackageReadyParam) {
        if (!runtimeEnabled) return
        if (!QDReaderHost.isTargetPackage(param.packageName)) return
        if (!param.isFirstPackage) {
            HookRuntime.w("Skip secondary package callback: package=${param.packageName}, process=$processName")
            return
        }
        HookRuntime.i(
            "Target package ready: package=${param.packageName}, process=$processName, " +
                "classLoader=${param.classLoader}",
        )
        targetClassLoader = param.classLoader
        targetPackageReady = true
        if (!shouldInstallFeaturesInProcess()) {
            HookRuntime.i("Skip feature install outside supported target processes: process=$processName")
            return
        }
        installLifecycleProbe(param.classLoader)
        FeatureRuntimeInstaller.installPackageReady(module, param.classLoader, processName)
    }

    fun onHotReloading(param: HotReloadingParam): Boolean {
        if (!runtimeEnabled) return true
        param.setSavedInstanceState(Bundle().apply {
            putString(STATE_PROCESS_NAME, processName)
            putBoolean(STATE_TARGET_PACKAGE_READY, targetPackageReady)
            putBoolean(STATE_APPLICATION_RETRY_DONE, applicationReadyRetryDone.get())
        })
        HookRuntime.i(
            "Hot reload requested: process=${QDReaderHost.processLabel(processName)}, " +
                "targetReady=$targetPackageReady, registrySize=${HookRuntime.registrySize()}",
        )
        RuntimeCleanupRegistry.runAndClear("hotReloading:${QDReaderHost.processLabel(processName)}")
        targetClassLoader = null
        HookRuntime.clearRegistry()
        HostApplicationInitializer.clear()
        HookRuntime.detach(module)
        return true
    }

    fun onHotReloaded(param: HotReloadedParam) {
        if (!enableForCurrentApi()) return
        com.xiyunmn.qdhelper.shared.compose.ModuleComposeEnvironment.configure(module.moduleApplicationInfo)
        HookRuntime.attach(module)
        attachResolverEnvironment()
        processName = param.processName
        val oldHandles = param.oldHookHandles
        val savedState = param.savedInstanceState as? Bundle
        targetPackageReady = savedState?.getBoolean(STATE_TARGET_PACKAGE_READY, false) ?: false
        applicationReadyRetryDone.set(savedState?.getBoolean(STATE_APPLICATION_RETRY_DONE, false) ?: false)
        HookRuntime.i(
            "Hot reloaded: process=${QDReaderHost.processLabel(processName)}, " +
                "oldHandles=${oldHandles.size}, targetReady=$targetPackageReady",
        )
        HookRuntime.prepareHotReloadReplacement(oldHandles)
        if (!shouldInstallFeaturesInProcess()) {
            HookRuntime.i("Hot reload completed outside supported target processes: process=$processName")
            releaseStaleOldHandles("unsupported process")
            return
        }
        val currentApplication = currentApplicationOrNull()
        val recoveredTargetReady = targetPackageReady ||
            QDReaderHost.isTargetPackage(currentApplication?.packageName.orEmpty())
        val classLoader = findTargetClassLoader(oldHandles) ?: currentApplication?.classLoader
        if (!recoveredTargetReady || classLoader == null) {
            HookRuntime.w(
                "Hot reload cannot reinstall target hooks yet: targetReady=$targetPackageReady, " +
                    "recoveredTargetReady=$recoveredTargetReady, classLoader=$classLoader",
            )
            releaseStaleOldHandles("missing target classloader")
            return
        }
        targetClassLoader = classLoader
        initializeCurrentApplicationIfAvailable(currentApplication)
        releaseStaleOldHandles("hot reload preinstall")
        HookRuntime.withFrameworkLogSuppressed {
            installLifecycleProbe(classLoader)
            FeatureRuntimeInstaller.installHotReloaded(module, classLoader, processName)
            currentApplicationOrNull()?.let { application ->
                retryAfterApplicationReady(classLoader, application, "hotReload:${application.javaClass.name}")
            }
        }
        releaseStaleOldHandles("hot reload")
    }

    private fun retryAfterApplicationReady(
        classLoader: ClassLoader,
        application: Application,
        applicationClassName: String,
    ) {
        if (!applicationReadyRetryDone.compareAndSet(false, true)) return
        HookRuntime.i(
            "Application ready retry: app=${QDReaderHost.classLabel(applicationClassName)}, " +
                "package=${application.packageName}",
        )
        FeatureRuntimeInstaller.retryApplicationReady(
            module = module,
            classLoader = classLoader,
            processName = processName,
            reason = QDReaderHost.classLabel(applicationClassName),
        )
    }

    private fun installLifecycleProbe(classLoader: ClassLoader) {
        HostLifecycleProbe.install(
            module = module,
            classLoader = classLoader,
            processName = processName,
            onApplicationReady = { application, applicationClassName ->
                retryAfterApplicationReady(classLoader, application, applicationClassName)
            },
        )
    }

    private fun initializeCurrentApplicationIfAvailable(application: Application? = currentApplicationOrNull()) {
        if (application == null) {
            HookRuntime.w("Hot reload: current Application is not available yet")
            return
        }
        HostApplicationInitializer.initialize(application, processName, "hotReload:${application.javaClass.name}")
        HostApplicationInitializer.markApplication(application, "hotReload")
        HookRuntime.i("Hot reload initialized current Application: ${QDReaderHost.classLabel(application.javaClass.name)}")
    }

    private fun currentApplicationOrNull(): Application? {
        return ApplicationLifecycleBridge.currentApplicationOrNull { throwable ->
            HookRuntime.w("Hot reload: current Application lookup failed: ${throwable.javaClass.name}")
        }
    }

    private fun findTargetClassLoader(handles: List<XposedInterface.HookHandle>): ClassLoader? {
        return handles.firstNotNullOfOrNull { handle ->
            val executable = runCatching { handle.executable }.getOrNull() ?: return@firstNotNullOfOrNull null
            if (executable.declaringClass.name.startsWith(QDReaderHost.PACKAGE + ".")) {
                executable.declaringClass.classLoader
            } else {
                null
            }
        }
    }

    private fun releaseStaleOldHandles(reason: String) {
        val handles = HookRuntime.drainStaleHotReloadHandles()
        var unhooked = 0
        handles.forEach { handle ->
            runCatching {
                handle.unhook()
                unhooked += 1
            }.onFailure { throwable ->
                HookRuntime.w("Hot reload old hook unhook failed: ${throwable.javaClass.name}")
            }
        }
        HookRuntime.i("Hot reload stale hooks released: reason=$reason, unhooked=$unhooked/${handles.size}")
    }

    private fun shouldInstallFeaturesInProcess(): Boolean =
        QDReaderHost.isMainProcess(processName) || QDReaderHost.isMiniProcess(processName)

    private fun enableForCurrentApi(): Boolean {
        val status = AndroidApiSupport.status()
        if (status == AndroidApiSupport.Status.UNSUPPORTED) {
            runtimeEnabled = false
            module.log(
                ANDROID_LOG_INFO,
                TAG,
                "Module disabled: ${AndroidApiSupport.summary()}",
            )
            return false
        }
        runtimeEnabled = true
        if (status == AndroidApiSupport.Status.UNVERIFIED &&
            unverifiedApiWarningLogged.compareAndSet(false, true)
        ) {
            module.log(
                ANDROID_LOG_INFO,
                TAG,
                "Module running on an unverified Android API: ${AndroidApiSupport.summary()}",
            )
        }
        return true
    }

    private fun attachResolverEnvironment() {
        ResolverEnvironment.configure(info = HookRuntime::i, warn = HookRuntime::w)
        RuntimeCleanupRegistry.register("host-resolver") { ResolverEnvironment.close() }
    }

    private companion object {
        const val ANDROID_LOG_INFO = 4
        const val TAG = "QDHelper.Module"
        const val STATE_PROCESS_NAME = "processName"
        const val STATE_TARGET_PACKAGE_READY = "targetPackageReady"
        const val STATE_APPLICATION_RETRY_DONE = "applicationReadyRetryDone"
    }
}
