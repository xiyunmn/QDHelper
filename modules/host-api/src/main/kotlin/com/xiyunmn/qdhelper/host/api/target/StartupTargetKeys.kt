package com.xiyunmn.qdhelper.host.api.target

/** Startup targets whose misses are deferred to background resolution for the next launch. */
object StartupTargetKeys {
    val splashSkipAd = method("startup.splash.skip_ad")
    val splashDownloadCacheDir = method("startup.splash.download_util.cihai")
    val splashActivityInitCustom = method("startup.splash.activity.init_custom")
    val splashActivityInitDefault = method("startup.splash.activity.init_default")
    val splashConfigButtonBind = method("startup.splash.config.button_bind")

    val all: Set<TargetKey.MethodTarget> = linkedSetOf(
        splashSkipAd,
        splashDownloadCacheDir,
        splashActivityInitCustom,
        splashActivityInitDefault,
        splashConfigButtonBind,
    )

    private fun method(id: String) = TargetKey.MethodTarget(TargetId(id), Cardinality.One)
}
