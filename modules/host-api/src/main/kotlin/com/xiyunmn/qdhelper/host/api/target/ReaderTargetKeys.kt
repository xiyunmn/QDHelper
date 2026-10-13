package com.xiyunmn.qdhelper.host.api.target

/**
 * Stable reader-domain target contracts consumed by the reader feature.
 *
 * The key objects live in host-api so a feature never needs to know about a
 * DexKit query or a host-resolver implementation. Cardinality
 * is part of the contract: a multi-target result is accepted only when the
 * resolver can prove that every returned member belongs to the bounded set.
 */
object ReaderTargetKeys {
    val chapterEndAppend = TargetKey.MethodTarget(
        TargetId("reader.clean.chapter_end.append"),
        Cardinality.One,
    )

    val chapterCommentCopyBindMethods = TargetKey.MethodTarget(
        TargetId("reader.free_copy.chapter_comment_bind"),
        Cardinality.Many(
            min = 1,
            max = 256,
            justification = "One bind method per supported chapter-comment holder; the host catalog bounds the set.",
        ),
    )
}
