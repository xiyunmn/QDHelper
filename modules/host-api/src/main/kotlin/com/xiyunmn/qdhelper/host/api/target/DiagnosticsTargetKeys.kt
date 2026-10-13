package com.xiyunmn.qdhelper.host.api.target

/** Explicit-action diagnostics targets; never resolved by default prewarm. */
object DiagnosticsTargetKeys {
    val hostCookieGetter = TargetKey.MethodTarget(
        TargetId("debug.host_cookie.getter"),
        Cardinality.One,
    )

    val hostOaidGetter = TargetKey.MethodTarget(
        TargetId("debug.host_oaid.getter"),
        Cardinality.One,
    )

    val deviceQdInfoCandidates = TargetKey.MethodTarget(
        TargetId("debug.device_identity_probe.qdinfo_candidates"),
        Cardinality.Many(
            min = 1,
            max = 2,
            justification = "audited hosts may expose plaintext and encrypted String(long,String) overloads",
        ),
    )
    val commonDeviceIdentitySetters = TargetKey.MethodTarget(
        TargetId("debug.device_identity_probe.common_device_setters"),
        Cardinality.Many(
            min = 3,
            max = 3,
            justification = "common.lib.d exposes exactly qimei16, qimei36, and OAID static String setters",
        ),
    )
    val commonDeviceOstar16Setter = method("debug.device_identity_probe.ostar16_setter")
    val commonDeviceOstar36Setter = method("debug.device_identity_probe.ostar36_setter")
    val configLoaderRequest = method("debug.device_identity_probe.config_loader_request")
    val configLoaderApply = method("debug.device_identity_probe.config_loader_apply")
    val appConfigCacheLoaded = method("debug.device_identity_probe.app_config_cache_loaded")
    val appConfigNetworkUpdated = method("debug.device_identity_probe.app_config_network_updated")
    val getconfApi = method("debug.device_identity_probe.getconf_api")

    val all: Set<TargetKey.MethodTarget> = linkedSetOf(
        hostCookieGetter,
        hostOaidGetter,
        deviceQdInfoCandidates,
        commonDeviceIdentitySetters,
        commonDeviceOstar16Setter,
        commonDeviceOstar36Setter,
        configLoaderRequest,
        configLoaderApply,
        appConfigCacheLoaded,
        appConfigNetworkUpdated,
        getconfApi,
    )

    private fun method(id: String) = TargetKey.MethodTarget(TargetId(id), Cardinality.One)
}
