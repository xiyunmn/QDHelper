package com.xiyunmn.qdhelper.feature.welfare.hook

import com.xiyunmn.qdhelper.feature.api.runtime.InstallableFeature
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule

internal enum class WelfareRewardInstallMode(
    val logLabel: String,
    val rewardBypassAllowed: Boolean,
) {
    PROBE_ONLY("probe", false),
    ACTION_CAPABLE("action", true),
}

object WelfareRewardActionFeature : InstallableFeature {
    override val name: String = "WelfareRewardActionFeature"

    override fun install(
        module: HookModule,
        classLoader: ClassLoader,
        processName: String,
        phase: String,
    ) {
        WelfareRewardProbeFeature.installInternal(
            module = module,
            classLoader = classLoader,
            processName = processName,
            phase = phase,
            mode = WelfareRewardInstallMode.ACTION_CAPABLE,
        )
    }
}
