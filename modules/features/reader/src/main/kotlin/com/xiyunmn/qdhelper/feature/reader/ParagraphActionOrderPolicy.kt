package com.xiyunmn.qdhelper.feature.reader

object ParagraphActionOrderPolicy {
    val defaultKeys = listOf(
        "comment",
        "listen",
        "dictionary",
        "mark",
        "reward",
        "share",
        "dubbing",
        "correction",
        "copy",
    )

    fun normalize(raw: String?): List<String> {
        val stored = raw
            ?.split(',')
            ?.map(String::trim)
            ?.filter(defaultKeys::contains)
            .orEmpty()
        return (stored + defaultKeys).distinct()
    }

}
