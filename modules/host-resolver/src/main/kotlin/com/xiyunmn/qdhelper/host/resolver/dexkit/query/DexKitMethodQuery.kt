package com.xiyunmn.qdhelper.host.resolver.dexkit.query

import org.luckypray.dexkit.query.FindMethod
import java.lang.reflect.Method

class DexKitMethodQuery internal constructor(
    val id: String,
    val purpose: String,
    internal val query: FindMethod.() -> Unit,
    internal val verify: (Method) -> Boolean,
)
