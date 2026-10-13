package com.qidian.morphing

data class MorphingCard(
    val viewType: Int,
    val attr: Any? = null,
    val data: Any? = null,
    val groupId: Int = -1,
    val trackViewType: Int = 0,
    val tabIndex: Int = 0,
)
