package com.xiyunmn.qdhelper.host.api.target

/** Stable native-list book-shield target contracts. */
object ShieldTargetKeys {
    val bookstoreReborn = method("shield.list_setter.bookstore_reborn")
    val bookItem = method("shield.list_setter.book_item")
    val bookstoreItem = method("shield.list_setter.bookstore_item")
    val bookstoreGroup = method("shield.list_setter.bookstore_group")
    val bookstoreLimit = method("shield.list_setter.bookstore_limit")
    val bookRecommend = method("shield.list_setter.book_recommend")
    val sanJiang = method("shield.list_setter.san_jiang")
    val rankingRight = method("shield.list_setter.ranking_right")
    val comicSquare = method("shield.list_setter.comic_square")

    val all: Set<TargetKey.MethodTarget> = linkedSetOf(
        bookstoreReborn,
        bookItem,
        bookstoreItem,
        bookstoreGroup,
        bookstoreLimit,
        bookRecommend,
        sanJiang,
        rankingRight,
        comicSquare,
    )

    private fun method(id: String) = TargetKey.MethodTarget(TargetId(id), Cardinality.One)
}
