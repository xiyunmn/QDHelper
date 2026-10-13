package com.xiyunmn.qdhelper.host.api.target

/** Stable bottom-navigation host target contracts. */
object NavigationTargetKeys {
    val redPoint = method("ui.bottom_tab.param.red_point")
    val tagText = method("ui.bottom_tab.param.tag_text")
    val operationRedDot = method("ui.bottom_tab.param.operation_red_dot")
    val trackerPosition = method("ui.bottom_tab.param.tracker_position")
    val operationMarker = method("ui.bottom_tab.param.operation_marker")
    val setRedPoint = method("ui.bottom_tab.param.set_red_point")
    val setTagText = method("ui.bottom_tab.param.set_tag_text")
    val setOperationRedDot = method("ui.bottom_tab.param.set_operation_red_dot")
    val pagerRebuild = method("ui.bottom_tab.pager.rebuild")
    val adapterItems = method("ui.bottom_tab.adapter.items")
    val renderIcon = method("ui.bottom_tab.pager.render_icon")

    val all: Set<TargetKey.MethodTarget> = linkedSetOf(
        redPoint,
        tagText,
        operationRedDot,
        trackerPosition,
        operationMarker,
        setRedPoint,
        setTagText,
        setOperationRedDot,
        pagerRebuild,
        adapterItems,
        renderIcon,
    )

    private fun method(id: String) = TargetKey.MethodTarget(TargetId(id), Cardinality.One)
}
