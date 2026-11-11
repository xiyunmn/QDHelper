package com.xiyunmn.qdhelper.host.api.target

object MediaExportTargetKeys {
    val stickerDetailBind = method("sticker_detail_bind")
    val voiceListBind = method("voice_list_bind", Cardinality.Many(1, 8, "Chapter-comment list holders with voice playback"))
    val voiceDetailBind = method("voice_detail_bind", Cardinality.Many(1, 4, "Main and reply voice holders on the comment detail page"))

    val stickers = setOf(stickerDetailBind)
    val voice = setOf(voiceListBind, voiceDetailBind)
    val all = stickers + voice

    private fun method(name: String, cardinality: Cardinality = Cardinality.One) =
        TargetKey.MethodTarget(TargetId("content_tools.media_export.$name"), cardinality)
}
