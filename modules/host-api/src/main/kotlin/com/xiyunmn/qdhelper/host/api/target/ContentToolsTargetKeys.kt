package com.xiyunmn.qdhelper.host.api.target

/** Stable content-tools host target contracts. */
object ContentToolsTargetKeys {
    val profileHomeResponseConvert = method("content_tools.profile_privacy.home_response_convert")
    val profileChapterReviewsApi = method("content_tools.profile_privacy.chapter_reviews_api")
    val profileBookReviewsApi = method("content_tools.profile_privacy.book_reviews_api")
    val profilePreloadCallbackSuccess = method("content_tools.profile_privacy.preload_callback_success")
    val profileColumnsApi = method("content_tools.profile_privacy.columns_api")
    val profileBookListApi = method("content_tools.profile_privacy.book_list_api")
    val profileUserFollowApi = method("content_tools.profile_privacy.user_follow_api")
    val profileUserAudioListApi = method("content_tools.profile_privacy.user_audio_list_api")
    val profileCapsuleApi = method("content_tools.profile_privacy.capsule_api")
    val profileCvApi = method("content_tools.profile_privacy.cv_api")

    val profilePrivacyRestore: Set<TargetKey.MethodTarget> = linkedSetOf(
        profileHomeResponseConvert,
        profileChapterReviewsApi,
        profileBookReviewsApi,
        profilePreloadCallbackSuccess,
        profileColumnsApi,
        profileBookListApi,
        profileUserFollowApi,
        profileUserAudioListApi,
        profileCapsuleApi,
        profileCvApi,
    )

    val all: Set<TargetKey.MethodTarget> = profilePrivacyRestore

    private fun method(id: String) = TargetKey.MethodTarget(TargetId(id), Cardinality.One)
}
