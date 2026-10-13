package com.xiyunmn.qdhelper.host.api.target

import java.lang.reflect.Method

@JvmInline
value class TargetId(val value: String) {
    init {
        require(ID_PATTERN.matches(value)) { "Invalid target ID: $value" }
    }

    override fun toString(): String = value

    private companion object {
        val ID_PATTERN = Regex("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*")
    }
}

sealed class TargetKey<T : Any>(
    val id: TargetId,
    val valueType: Class<T>,
    val cardinality: Cardinality,
) {
    class MethodTarget(id: TargetId, cardinality: Cardinality = Cardinality.One) :
        TargetKey<Method>(id, Method::class.java, cardinality)
}

sealed interface Cardinality {
    data object One : Cardinality
    data class Many(val min: Int, val max: Int, val justification: String) : Cardinality {
        init {
            require(min >= 0 && max >= min)
            require(justification.isNotBlank())
        }
    }
}

sealed interface Resolution<out T : Any> {
    data class Resolved<T : Any>(val value: T, val source: Source) : Resolution<T>
    data class ResolvedMany<T : Any>(val values: List<T>, val source: Source) : Resolution<T>
    data class Unavailable(val reason: String, val retryable: Boolean) : Resolution<Nothing>
    data class Ambiguous(val candidateCount: Int, val reason: String) : Resolution<Nothing>

    enum class Source {
        MEMORY,
        CACHE,
        DEXKIT,
    }
}

interface HostContractResolver {
    fun <T : Any> resolve(key: TargetKey<T>, forceRescan: Boolean = false): Resolution<T>
}
