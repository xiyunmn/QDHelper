package com.xiyunmn.qdhelper.host.api.target

/** Stable book-friend-circle target contracts. */
object CircleTargetKeys {
    val listBind = TargetKey.MethodTarget(
        TargetId("circle.free_copy.list_bind"),
        Cardinality.One,
    )

    val detailBindMethods = TargetKey.MethodTarget(
        TargetId("circle.free_copy.detail_bind"),
        Cardinality.Many(
            min = 1,
            max = 256,
            justification = "One bind method per supported rich-text holder; the host catalog bounds the set.",
        ),
    )

    val detailData = TargetKey.MethodTarget(
        TargetId("circle.free_copy.detail_data"),
        Cardinality.One,
    )

    /** Complete circle contract set; consumers should use this instead of hand-written lists. */
    val all: List<TargetKey.MethodTarget> = listOf(
        listBind,
        detailBindMethods,
        detailData,
    )
}
