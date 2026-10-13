package com.xiyunmn.qdhelper.feature.reader

import com.xiyunmn.qdhelper.feature.api.runtime.FeatureGateSupport
import com.xiyunmn.qdhelper.feature.api.runtime.InstallableFeature
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import com.xiyunmn.qdhelper.config.SettingsRuntime

object ReaderPrivacyFeature : InstallableFeature {
    override val name: String = "ReaderPrivacyFeature"

    override fun install(module: HookModule, classLoader: ClassLoader, processName: String, phase: String) {
        FeatureGateSupport.installNoArgNoOp(
            module = module,
            classLoader = classLoader,
            phase = phase,
            id = "reader.watermark",
            hookId = "qdhelper.watermark.reader",
            className = HostSymbols.Classes.QD_READER_ACTIVITY,
            methodName = "setWaterMark",
            shouldBlock = SettingsRuntime::isReaderWatermarkBlockEnabled,
        )
    }
}
