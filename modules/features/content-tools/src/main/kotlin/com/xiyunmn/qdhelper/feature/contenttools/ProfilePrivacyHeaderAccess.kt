package com.xiyunmn.qdhelper.feature.contenttools

internal object ProfilePrivacyHeaderAccess {
    private const val ALL_SUB_BASE_URL = "https://h5.if.qidian.com/new/yueli/fullAll/"

    fun resolveAllSubUrl(
        hostUrl: String?,
        isPrivacyProfile: Boolean,
        userId: Long,
    ): String? {
        hostUrl?.takeIf(String::isNotBlank)?.let { return it }
        if (!isPrivacyProfile || userId <= 0L) return null
        return "$ALL_SUB_BASE_URL?_viewmode=0&from=personalpage&userId=$userId"
    }
}
