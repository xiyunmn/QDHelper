package com.xiyunmn.qdhelper.feature.reader

import com.xiyunmn.qdhelper.feature.api.runtime.InstallableFeature
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule

object ReaderPrivacyFeature : InstallableFeature {
    override val name: String = "ReaderPrivacyFeature"

    override fun install(module: HookModule, classLoader: ClassLoader, processName: String, phase: String) {
        ReaderWatermarkGuard.install(module, classLoader, processName, phase)
    }
}
