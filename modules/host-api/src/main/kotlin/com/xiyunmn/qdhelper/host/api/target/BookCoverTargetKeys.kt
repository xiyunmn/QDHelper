package com.xiyunmn.qdhelper.host.api.target

/** Cover-only host contracts, resolved on the original-image worker. */
object BookCoverTargetKeys {
    val bookManagerInstance = method("content_tools.cover.book_manager.instance")
    val bookInfoById = method("content_tools.cover.book_manager.book_info_by_id")
    val coverUrl600 = method("content_tools.cover.url_600")
    val all = setOf(bookManagerInstance, bookInfoById, coverUrl600)

    private fun method(id: String) = TargetKey.MethodTarget(TargetId(id), Cardinality.One)
}
