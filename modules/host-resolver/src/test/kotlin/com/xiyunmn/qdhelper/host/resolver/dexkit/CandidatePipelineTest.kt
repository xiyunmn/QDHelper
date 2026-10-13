package com.xiyunmn.qdhelper.host.resolver.dexkit

import com.xiyunmn.qdhelper.host.api.target.Cardinality
import com.xiyunmn.qdhelper.host.api.target.TargetId
import com.xiyunmn.qdhelper.host.api.target.TargetKey
import com.xiyunmn.qdhelper.host.resolver.catalog.HostTargetSpec
import com.xiyunmn.qdhelper.host.resolver.catalog.PrewarmPolicy
import com.xiyunmn.qdhelper.host.resolver.catalog.ResolutionTiming
import com.xiyunmn.qdhelper.host.resolver.catalog.SemanticSpec
import com.xiyunmn.qdhelper.host.resolver.catalog.TargetRisk
import com.xiyunmn.qdhelper.host.resolver.dexkit.query.DexKitMethodQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Method

class CandidatePipelineTest {
    @Test
    fun verifierFailureDoesNotDiscardOtherValidCandidate() {
        val valid = Fixture::class.java.getDeclaredMethod("valid")
        val malformed = Fixture::class.java.getDeclaredMethod("malformed")
        val spec = spec()

        val result = CandidatePipeline.evaluate(spec, listOf(valid, malformed))

        assertEquals(CandidateStatus.PASS, result.status)
        assertEquals(1, result.methods.size)
        assertEquals("valid", result.methods.single().name)
        assertEquals(1, result.verifierErrorCount)
        assertTrue(CandidatePipeline.matchesCardinality(spec.cardinality, result.methods.size))
    }

    @Test
    fun ambiguitySurvivesVerifierErrorsAndIsNotAnUnresolvedTarget() {
        val result = CandidatePipeline.evaluate(spec(), methods("valid", "another", "malformed"))

        assertEquals(CandidateStatus.AMBIGUOUS, result.status)
        assertEquals(2, result.methods.size)
        assertEquals(1, result.verifierErrorCount)
    }

    @Test
    fun emptyMatchAndBrokenVerifierRemainDistinct() {
        assertEquals(CandidateStatus.UNRESOLVED, CandidatePipeline.evaluate(spec(), emptyList()).status)
        assertEquals(CandidateStatus.UNRESOLVED, CandidatePipeline.evaluate(spec(), methods("rejected")).status)
        assertEquals(CandidateStatus.VERIFIER_ERROR, CandidatePipeline.evaluate(spec(), methods("malformed")).status)
        assertEquals(
            CandidateStatus.VERIFIER_ERROR,
            CandidatePipeline.evaluate(spec(), emptyList(), rawCandidateCount = 1, initialVerifierErrorCount = 1).status,
        )
    }

    @Test
    fun duplicatesAreNormalizedBeforeCardinalityValidation() {
        val result = CandidatePipeline.evaluate(spec(), methods("valid", "valid", "malformed"))

        assertEquals(CandidateStatus.PASS, result.status)
        assertEquals(3, result.rawCandidateCount)
        assertEquals(2, result.verifiedCandidateCount)
        assertEquals(1, result.deduplicatedCandidateCount)
    }

    @Test
    fun manyTargetsRejectUnderflowAndOverflowWithoutLosingCandidates() {
        val spec = spec(Cardinality.Many(2, 2, "test bounds"))

        assertEquals(CandidateStatus.AMBIGUOUS, CandidatePipeline.evaluate(spec, methods("valid")).status)
        val accepted = CandidatePipeline.evaluate(spec, methods("another", "valid", "valid", "malformed"))
        assertEquals(CandidateStatus.PASS, accepted.status)
        assertEquals(listOf("another", "valid"), accepted.methods.map { it.name })
        val overflow = CandidatePipeline.evaluate(spec(Cardinality.Many(1, 1, "test bounds")), methods("valid", "another"))
        assertEquals(CandidateStatus.AMBIGUOUS, overflow.status)
        assertEquals(2, overflow.methods.size)
    }

    private fun methods(vararg names: String): List<Method> = names.map { Fixture::class.java.getDeclaredMethod(it) }

    private fun spec(cardinality: Cardinality = Cardinality.One) = HostTargetSpec(
        key = TargetKey.MethodTarget(TargetId("test.pipeline"), cardinality),
        revision = 1,
        owner = com.xiyunmn.qdhelper.foundation.identity.OwnerId("test"),
        purpose = "test",
        risk = TargetRisk.LOW,
        process = "main",
        timing = ResolutionTiming.RUNTIME_INSTALLABLE,
        prewarm = PrewarmPolicy.NEVER,
        cardinality = cardinality,
        semantic = SemanticSpec("test", "test"),
        query = DexKitMethodQuery(
            id = "test.pipeline",
            purpose = "test",
            query = {},
            verify = { method ->
                if (method.name == "malformed") error("synthetic verifier failure")
                method.name != "rejected"
            },
        ),
    )

    private class Fixture {
        fun valid() = Unit
        fun another() = Unit
        fun malformed() = Unit
        fun rejected() = Unit
    }
}
