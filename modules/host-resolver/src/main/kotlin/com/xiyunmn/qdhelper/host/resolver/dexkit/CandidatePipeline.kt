package com.xiyunmn.qdhelper.host.resolver.dexkit

import com.xiyunmn.qdhelper.host.api.target.Cardinality
import com.xiyunmn.qdhelper.host.resolver.catalog.HostTargetSpec
import java.lang.reflect.Method
import java.util.LinkedHashMap

/** The four outcomes emitted by the host-contract audit for one target. */
internal enum class CandidateStatus {
    PASS,
    UNRESOLVED,
    AMBIGUOUS,
    VERIFIER_ERROR,
}

/** Result of the shared raw -> verifier -> descriptor -> cardinality pipeline. */
internal data class CandidatePipelineResult(
    val rawCandidateCount: Int,
    val verifiedCandidateCount: Int,
    val deduplicatedCandidateCount: Int,
    val cardinality: String,
    val status: CandidateStatus,
    val methods: List<Method>,
    val verifierErrorCount: Int,
)

internal object CandidatePipeline {
    fun evaluate(
        spec: HostTargetSpec,
        rawMethods: List<Method>,
        rawCandidateCount: Int = rawMethods.size,
        initialVerifierErrorCount: Int = 0,
    ): CandidatePipelineResult {
        val verified = mutableListOf<Method>()
        var verifierErrors = initialVerifierErrorCount
        rawMethods.forEach { method ->
            runCatching {
                if (spec.query.verify(method)) verified += method
            }.onFailure {
                verifierErrors += 1
            }
        }
        val unique = LinkedHashMap<String, Method>()
        verified.forEach { method -> unique.putIfAbsent(method.toGenericString(), method) }
        val methods = unique.values.sortedBy(Method::toGenericString)
        val status = when {
            methods.isEmpty() -> if (verifierErrors > 0) CandidateStatus.VERIFIER_ERROR else CandidateStatus.UNRESOLVED
            !matchesCardinality(spec.cardinality, methods.size) -> CandidateStatus.AMBIGUOUS
            else -> CandidateStatus.PASS
        }
        return CandidatePipelineResult(
            rawCandidateCount = rawCandidateCount,
            verifiedCandidateCount = verified.size,
            deduplicatedCandidateCount = methods.size,
            cardinality = cardinalityLabel(spec.cardinality),
            status = status,
            methods = methods,
            verifierErrorCount = verifierErrors,
        )
    }

    fun matchesCardinality(cardinality: Cardinality, count: Int): Boolean = when (cardinality) {
        Cardinality.One -> count == 1
        is Cardinality.Many -> count in cardinality.min..cardinality.max
    }

    fun cardinalityLabel(cardinality: Cardinality): String = when (cardinality) {
        Cardinality.One -> "one"
        is Cardinality.Many -> "many:${cardinality.min}-${cardinality.max}"
    }
}
