package com.xiyunmn.qdhelper.host.api.target

/** Stable page-customization host target contracts. */
object PageCustomizationTargetKeys {
    val mineHolderBindData = method("ui.page_custom.mine.holder_bind_data")
    val mineProcessAccountItem = method("ui.page_custom.mine.process_account_item")
    val mineVisibilityChanged = method("ui.mine_page.visibility_changed")
    val mineReload = method("ui.mine_page.reload")

    val all: Set<TargetKey.MethodTarget> = linkedSetOf(
        mineHolderBindData,
        mineProcessAccountItem,
        mineVisibilityChanged,
        mineReload,
    )

    private fun method(id: String) = TargetKey.MethodTarget(TargetId(id), Cardinality.One)
}
