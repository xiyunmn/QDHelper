package com.xiyunmn.qdhelper.feature.account

import java.lang.reflect.Method

internal object RxConsumerSubscriptionMethodPolicy {
    fun select(
        methods: List<Method>,
        hasSingleArgumentAccept: (Class<*>) -> Boolean,
    ): Method? = methods.singleOrNull { method ->
        method.name == "subscribe" &&
            method.parameterTypes.size == 2 &&
            method.parameterTypes[0] == method.parameterTypes[1] &&
            method.parameterTypes[0].isInterface &&
            hasSingleArgumentAccept(method.parameterTypes[0])
    }
}
