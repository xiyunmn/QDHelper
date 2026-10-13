package com.xiyunmn.qdhelper.feature.startup

import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureGateSupport
import com.xiyunmn.qdhelper.feature.api.runtime.InstallableFeature
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import com.xiyunmn.qdhelper.config.SettingsRuntime

object StartupPrivacyFeature : InstallableFeature {
    override val name: String = "StartupPrivacyFeature"

    override fun install(module: HookModule, classLoader: ClassLoader, processName: String, phase: String) {
        var hooks = 0
        hooks += FeatureGateSupport.installNoArgNoOp(
            module, classLoader, phase, "startup.reportRom", "qdhelper.report.splash.rom",
            HostSymbols.Classes.SPLASH_ACTIVITY, "reportUserRom", SettingsRuntime::isStartupReportBlockEnabled,
        )
        hooks += FeatureGateSupport.installNoArgNoOp(
            module, classLoader, phase, "startup.reportTimezone", "qdhelper.report.splash.timezone",
            HostSymbols.Classes.SPLASH_ACTIVITY, "reportUserCurrentTimeZone", SettingsRuntime::isStartupReportBlockEnabled,
        )
        hooks += FeatureGateSupport.installNoArgNoOp(
            module, classLoader, phase, "main.downloadGame", "qdhelper.startup.download_game",
            HostSymbols.Classes.MAIN_GROUP_ACTIVITY, "downloadGame", SettingsRuntime::isDownloadGameBlockEnabled,
        )
        FeatureHooks.i("Startup privacy gates finished: phase=$phase, process=$processName, installedHooks=$hooks")
    }
}
