package com.xiyunmn.qdhelper.feature.networkidentity

import android.content.Context
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureGateSupport
import com.xiyunmn.qdhelper.feature.api.runtime.InstallableFeature
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import com.xiyunmn.qdhelper.config.SettingsRuntime

object NetworkPrivacyFeature : InstallableFeature {
    override val name: String = "NetworkPrivacyFeature"

    override fun install(module: HookModule, classLoader: ClassLoader, processName: String, phase: String) {
        var hooks = FeatureGateSupport.installNoArgNoOp(
            module, classLoader, phase, "main.trackDeviceInfo", "qdhelper.report.main.device",
            HostSymbols.Classes.MAIN_GROUP_ACTIVITY, "trackDeviceInfo", SettingsRuntime::isTrackDeviceInfoBlockEnabled,
        )
        val owner = FeatureHooks.findClassOrNull(HostSymbols.Classes.FOCK_UTIL, classLoader)
        val method = owner?.let {
            FeatureHooks.findDeclaredMethodOrNull(
                it,
                "reportDeviceInfo",
                Context::class.java,
                Boolean::class.javaPrimitiveType ?: Boolean::class.java,
            )
        }
        if (method != null) {
            hooks += FeatureGateSupport.installVoidNoOp(
                module, phase, "fock.reportDeviceInfo", "qdhelper.report.fock.device",
                method, SettingsRuntime::isFockReportBlockEnabled,
            )
        }
        FeatureHooks.i("Network privacy gates finished: phase=$phase, process=$processName, installedHooks=$hooks")
    }
}
