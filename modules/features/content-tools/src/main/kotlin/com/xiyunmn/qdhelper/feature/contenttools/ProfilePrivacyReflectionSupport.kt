package com.xiyunmn.qdhelper.feature.contenttools

import com.xiyunmn.qdhelper.host.api.reflection.HostAccess

internal object ProfilePrivacyReflectionSupport {
    fun defaultProxyValue(returnType: Class<*>): Any? {
        if (returnType == Void.TYPE || !returnType.isPrimitive) return null
        return when (returnType) {
            Boolean::class.javaPrimitiveType -> false
            Char::class.javaPrimitiveType -> 0.toChar()
            Byte::class.javaPrimitiveType -> 0.toByte()
            Short::class.javaPrimitiveType -> 0.toShort()
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Float::class.javaPrimitiveType -> 0f
            Double::class.javaPrimitiveType -> 0.0
            else -> null
        }
    }

    fun hostAccessLabel(result: HostAccess<*>): String = when (result) {
        is HostAccess.Value -> "value"
        HostAccess.Missing -> "missing"
        is HostAccess.Ambiguous -> "ambiguous(${result.candidateCount})"
        is HostAccess.Failed -> "failed(${result.errorType})"
    }
}
