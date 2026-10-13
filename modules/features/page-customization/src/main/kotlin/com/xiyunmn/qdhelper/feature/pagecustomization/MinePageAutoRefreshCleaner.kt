package com.xiyunmn.qdhelper.feature.pagecustomization

import com.xiyunmn.qdhelper.feature.api.runtime.UiFeatureSupport

import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.host.api.target.HostTargetResolver
import com.xiyunmn.qdhelper.host.api.target.PageCustomizationTargetKeys
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols as QDReaderHost
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import java.lang.reflect.Field
import java.lang.reflect.Method

object MinePageAutoRefreshCleaner {
    private const val REFRESH_LISTENER =
        "androidx.swiperefreshlayout.widget.SwipeRefreshLayout\$OnRefreshListener"

    fun install(
        module: HookModule,
        classLoader: ClassLoader,
        processName: String,
        phase: String,
    ): Int {
        val fragmentClass = FeatureHooks.findClassOrNull(
            QDReaderHost.Classes.USER_ACCOUNT_REBORN_FRAGMENT,
            classLoader,
        ) ?: return 0
        var installedHooks = installProgrammaticRefreshIndicatorGate(
            module = module,
            classLoader = classLoader,
            fragmentClass = fragmentClass,
            phase = phase,
        )
        val visibilityMethod = resolveVisibilityMethod(fragmentClass, classLoader, processName)
        val reloadMethod = resolveReloadMethod(fragmentClass, classLoader, processName)
        if (visibilityMethod == null || reloadMethod == null) {
            FeatureHooks.w(
                "Mine page auto-refresh hook skipped: visibility=${visibilityMethod != null}, " +
                    "reload=${reloadMethod != null}, phase=$phase",
            )
            return installedHooks
        }

        installedHooks += UiFeatureSupport.installAround(
            module = module,
            id = "ui.minePage.visibilityScope",
            method = visibilityMethod,
            hookId = "qdhelper.ui.mine_page_visibility_scope",
            phase = phase,
        ) { chain ->
            if (chain.args.getOrNull(0) == true) {
                MinePageAutoRefreshScope.withinVisibilityCallback { chain.proceed() }
            } else {
                chain.proceed()
            }
        }
        installedHooks += UiFeatureSupport.installAround(
            module = module,
            id = "ui.minePage.reloadGate",
            method = reloadMethod,
            hookId = "qdhelper.ui.mine_page_reload_gate",
            phase = phase,
        ) { chain ->
            val disabled = SettingsRuntime.isMinePageAutoRefreshDisabled(HostSessionStore.contextOrNull())
            if (MinePageAutoRefreshScope.shouldSuppressReload(disabled)) {
                null
            } else {
                chain.proceed()
            }
        }
        return installedHooks
    }

    private fun installProgrammaticRefreshIndicatorGate(
        module: HookModule,
        classLoader: ClassLoader,
        fragmentClass: Class<*>,
        phase: String,
    ): Int {
        val refreshLayoutClass = FeatureHooks.findClassOrNull(
            QDReaderHost.Classes.SUPER_REFRESH_LAYOUT,
            classLoader,
        ) ?: return 0
        val listenerField = findRefreshListenerField(refreshLayoutClass) ?: return 0
        val setRefreshingMethod = FeatureHooks.findDeclaredMethodOrNull(
            refreshLayoutClass,
            "setRefreshing",
            Boolean::class.javaPrimitiveType ?: Boolean::class.java,
        ) ?: return 0
        return UiFeatureSupport.installAround(
            module = module,
            id = "ui.minePage.programmaticRefreshIndicatorGate",
            method = setRefreshingMethod,
            hookId = "qdhelper.ui.mine_page_programmatic_refresh_indicator_gate",
            phase = phase,
        ) { chain ->
            val listener = runCatching { listenerField.get(chain.thisObject) }.getOrNull()
            val shouldSuppress = MinePageAutoRefreshScope.shouldSuppressRefreshIndicator(
                featureEnabled = SettingsRuntime.isMinePageAutoRefreshDisabled(HostSessionStore.contextOrNull()),
                requestedRefreshing = chain.args.getOrNull(0) == true,
                isMineLayout = fragmentClass.isInstance(listener),
            )
            if (shouldSuppress) {
                // Manual swipes are dispatched by SmartRefreshLayout directly and do not enter this true branch.
                null
            } else {
                chain.proceed()
            }
        }
    }

    private fun findRefreshListenerField(refreshLayoutClass: Class<*>): Field? {
        return FeatureHooks.allDeclaredFields(refreshLayoutClass)
            .singleOrNull { field -> field.type.name == REFRESH_LISTENER }
    }

    private fun resolveVisibilityMethod(
        fragmentClass: Class<*>,
        classLoader: ClassLoader,
        processName: String,
    ): Method? {
        return HostTargetResolver.resolveMethod(
            context = HostSessionStore.contextOrNull(),
            classLoader = classLoader,
            processName = processName,
            key = PageCustomizationTargetKeys.mineVisibilityChanged,
        )
    }

    private fun resolveReloadMethod(
        fragmentClass: Class<*>,
        classLoader: ClassLoader,
        processName: String,
    ): Method? {
        return HostTargetResolver.resolveMethod(
            context = HostSessionStore.contextOrNull(),
            classLoader = classLoader,
            processName = processName,
            key = PageCustomizationTargetKeys.mineReload,
        )
    }
}

internal object MinePageAutoRefreshScope {
    private val visibilityDepth = ThreadLocal<Int>()

    fun <T> withinVisibilityCallback(block: () -> T): T {
        val previousDepth = visibilityDepth.get() ?: 0
        visibilityDepth.set(previousDepth + 1)
        return try {
            block()
        } finally {
            if (previousDepth == 0) {
                visibilityDepth.remove()
            } else {
                visibilityDepth.set(previousDepth)
            }
        }
    }

    fun shouldSuppressReload(featureEnabled: Boolean): Boolean {
        return featureEnabled && (visibilityDepth.get() ?: 0) > 0
    }

    fun shouldSuppressRefreshIndicator(
        featureEnabled: Boolean,
        requestedRefreshing: Boolean,
        isMineLayout: Boolean,
    ): Boolean {
        return featureEnabled && requestedRefreshing && isMineLayout
    }
}
