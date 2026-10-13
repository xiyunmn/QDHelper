package com.xiyunmn.qdhelper.host.api.target

/** High-risk QIMEI/GUID targets resolved only by their enabled install unit. */
object NetworkIdentityTargetKeys {
    val commonDeviceQimei16 = method("network.identity.common_device.qimei16")
    val commonDeviceQimei36 = method("network.identity.common_device.qimei36")
    val cachedBeacon16 = method("network.identity.common_device_cached.beacon16")
    val cachedBeacon36 = method("network.identity.common_device_cached.beacon36")
    val cachedOstar16 = method("network.identity.common_device_cached.ostar16")
    val cachedOstar36 = method("network.identity.common_device_cached.ostar36")
    val fakeLocal16 = method("network.identity.fake_identity.fake_local16")
    val fakeLocal36 = method("network.identity.fake_identity.fake_local36")
    val fakeOstar16 = method("network.identity.fake_identity.fake_ostar16")
    val fakeOstar36 = method("network.identity.fake_identity.fake_ostar36")
    val fake16 = method("network.identity.fake_identity.fake16")
    val fake36 = method("network.identity.fake_identity.fake36")
    val accountYwGuid = method("identity.account.yw_guid_getter")

    val qimei: Set<TargetKey.MethodTarget> = linkedSetOf(
        commonDeviceQimei16,
        commonDeviceQimei36,
        cachedBeacon16,
        cachedBeacon36,
        cachedOstar16,
        cachedOstar36,
        fakeLocal16,
        fakeLocal36,
        fakeOstar16,
        fakeOstar36,
        fake16,
        fake36,
    )

    val guid: Set<TargetKey.MethodTarget> = linkedSetOf(accountYwGuid)
    val all: Set<TargetKey.MethodTarget> = qimei + guid

    private fun method(id: String) = TargetKey.MethodTarget(TargetId(id), Cardinality.One)
}
