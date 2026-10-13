package com.xiyunmn.qdhelper.host.api.target

/** Stable dialog-suppression host target contracts. */
object DialogTargetKeys {
    val autoUpdateEntry = method("dialog.update.auto_update_entry")
    val notificationRequest = method("dialog.notification.request")
    val notificationSceneGuide = method("dialog.notification.scene_guide")

    val all: Set<TargetKey.MethodTarget> = linkedSetOf(
        autoUpdateEntry,
        notificationRequest,
        notificationSceneGuide,
    )

    private fun method(id: String) = TargetKey.MethodTarget(TargetId(id), Cardinality.One)
}
