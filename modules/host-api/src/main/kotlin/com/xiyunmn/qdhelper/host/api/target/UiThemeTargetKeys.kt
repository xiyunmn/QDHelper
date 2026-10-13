package com.xiyunmn.qdhelper.host.api.target

/** Stable UI-theme targets shared by module-owned settings and host-like UI helpers. */
object UiThemeTargetKeys {
    val nightMode = TargetKey.MethodTarget(
        TargetId("ui.theme.night_mode"),
        Cardinality.One,
    )

    val all: Set<TargetKey.MethodTarget> = setOf(nightMode)
}
