package com.xiyunmn.qdhelper.feature.reader

import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols

/** Verified native initializers: legacy and the overload with spoiler actions (7.9.474+). */
internal object ChapterCommentMenuShape {
    private val legacy = listOf("int", "int", "boolean", "boolean", "int", "int", "int", "int", "boolean")
    fun matches(name: String, parameters: List<String>): Boolean =
        name == "initShow" && (parameters == legacy || parameters == legacy + HostSymbols.Classes.SPOILER_ACTION)
}
