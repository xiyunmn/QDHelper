package com.xiyunmn.qdhelper.feature.welfare.action

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * Adapt only a verified public Rx Consumer interface. The host receives the same object and
 * exception; invoking its existing consumer must not use a gateway that swallows exceptions.
 */
internal fun welfareConsumerProxy(
    consumerType: Class<*>,
    delegate: Any,
    dispatch: (value: Any?, proceed: () -> Unit) -> Unit,
): Any = Proxy.newProxyInstance(consumerType.classLoader, arrayOf(consumerType)) { proxy, method, args ->
    when {
        method.declaringClass == Any::class.java -> when (method.name) {
            "equals" -> proxy === args?.firstOrNull()
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "WelfareClaimConsumer"
            else -> null
        }
        else -> {
            dispatch(args?.firstOrNull()) {
                try {
                    method.invoke(delegate, *(args ?: emptyArray()))
                } catch (failure: InvocationTargetException) {
                    throw failure.targetException
                }
            }
            null
        }
    }
}
