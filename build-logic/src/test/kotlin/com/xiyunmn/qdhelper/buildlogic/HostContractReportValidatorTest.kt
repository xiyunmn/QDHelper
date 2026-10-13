package com.xiyunmn.qdhelper.buildlogic

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HostContractReportValidatorTest {
    private val catalogHash = "a".repeat(64)
    private val apkHash = "b".repeat(64)
    private val catalog = ExpectedHostCatalog(catalogHash, linkedMapOf(
        "one" to ExpectedHostTarget(7, "one", "test", "HIGH"),
        "many" to ExpectedHostTarget(2, "many:1-2", "test", "LOW"),
    ))

    @Test
    fun validatesTheCompleteCurrentContractAndWarnsWithoutDiscardingValidCandidates() {
        assertEquals("PASS", validate(report()).verdict)
        val warning = report().apply {
            target(0).addProperty("rawCandidateCount", 2)
            target(0).addProperty("verifierErrorCount", 1)
        }
        val result = validate(warning)
        assertEquals("PASS", result.verdict)
        assertEquals(1L, result.verifierErrors)
    }

    @Test
    fun rejectsCommandErrorsIncompleteJsonTrailingContentCommentsAndDuplicateFields() {
        val valid = report().toString()
        listOf(
            "run-as: package not found",
            "{",
            valid.dropLast(1),
            "$valid\ncommand failed",
            "$valid {}",
            "// comment\n$valid",
            valid.replaceFirst("\"schemaVersion\":3", "\"schemaVersion\":3,\"schemaVersion\":3"),
            "[${valid}]",
        ).forEach { invalid -> assertThrows(Exception::class.java) { validate(invalid) } }
    }

    @Test
    fun rejectsStaleIdentityWrongSchemaAndNoScanEvidence() {
        val edits: List<(JsonObject) -> Unit> = listOf(
            { it.addProperty("schemaVersion", 2) },
            { it.addProperty("toolVersion", 2) },
            { it.addProperty("hostVersion", "7.9.469") },
            { it.addProperty("hostVersionCode", "79469") },
            { it.addProperty("hostPackage", "other.package") },
            { it.addProperty("apkSha256", "c".repeat(64)) },
            { it.addProperty("catalogHash", "d".repeat(64)) },
            { it.addProperty("scanId", "old-scan") },
            { it.addProperty("scanPerformed", false) },
            { it.addProperty("scanPerformed", "true") },
            { it.addProperty("startedAtEpochMs", 0) },
            { it.addProperty("durationMs", -1) },
            { it.addProperty("overallVerdict", "NO_SCAN") },
        )
        edits.forEach { edit -> assertThrows(Exception::class.java) { validate(report().apply(edit)) } }
    }

    @Test
    fun rejectsWrongRevisionsAmbiguousCountsMissingTargetsAndRawOnlyPayloads() {
        val edits: List<(JsonObject) -> Unit> = listOf(
            { it.target(0).addProperty("revision", 6) },
            { it.target(0).addProperty("revision", 7.5) },
            { it.target(0).addProperty("owner", "other") },
            { it.target(0).addProperty("risk", "LOW") },
            { it.target(0).addProperty("cardinality", "many:1-2") },
            { it.target(0).addProperty("deduplicatedCandidateCount", 2) },
            { it.target(0).addProperty("verifiedCandidateCount", 0) },
            { it.target(0).addProperty("verifierErrorCount", -1) },
            { it.target(0).remove("status") },
            { it.target(0).addProperty("id", "unexpected") },
            { it.getAsJsonArray("targets").remove(1) },
            { it.getAsJsonArray("targets").add(it.target(0).deepCopy()) },
        )
        edits.forEach { edit -> assertThrows(Exception::class.java) { validate(report().apply(edit)) } }
    }

    @Test
    fun validFailuresAreArchivedAsPartialRatherThanPassing() {
        listOf("UNRESOLVED", "AMBIGUOUS", "VERIFIER_ERROR").forEach { status ->
            val report = report()
            val target = report.target(0)
            val count = if (status == "AMBIGUOUS") 2 else 0
            listOf("rawCandidateCount", "verifiedCandidateCount", "deduplicatedCandidateCount", "uniqueCandidateCount")
                .forEach { target.addProperty(it, count) }
            target.addProperty("status", status)
            if (status == "VERIFIER_ERROR") {
                target.addProperty("verifierErrorCount", 1)
                target.addProperty("error", "test.QueryException")
            }
            assertThrows(Exception::class.java) { validate(report) }
            report.addProperty("verdict", "PARTIAL")
            report.addProperty("overallVerdict", "PARTIAL")
            assertEquals("PARTIAL", validate(report).verdict)
        }
    }

    @Test
    fun expectedCatalogComesFromVersionedCompiledManifestNotTheReport() {
        val properties = Properties().apply {
            setProperty("schemaVersion", "3")
            setProperty("catalogHash", catalogHash)
            catalog.targets.forEach { (id, target) ->
                setProperty("target.$id.revision", target.revision.toString())
                setProperty("target.$id.cardinality", target.cardinality)
                setProperty("target.$id.owner", target.owner)
                setProperty("target.$id.risk", target.risk)
            }
        }
        assertEquals(catalog, ExpectedHostCatalog.from(properties))
        properties.setProperty("schemaVersion", "2")
        assertThrows(IllegalArgumentException::class.java) { ExpectedHostCatalog.from(properties) }
    }

    private fun report(): JsonObject = JsonParser.parseString("""
        {
          "schemaVersion":3,"toolVersion":3,"hostPackage":"com.qidian.QDReader",
          "hostVersion":"7.9.470","hostVersionCode":"79470","apkSha256":"$apkHash",
          "scanId":"current-scan","startedAtEpochMs":1788800000000,"durationMs":10,
          "scanPerformed":true,"catalogHash":"$catalogHash","verdict":"PASS","overallVerdict":"PASS",
          "targets":[
            {"id":"one","revision":7,"owner":"test","risk":"HIGH","cardinality":"one",
             "rawCandidateCount":1,"verifiedCandidateCount":1,"deduplicatedCandidateCount":1,
             "uniqueCandidateCount":1,"verifierErrorCount":0,"status":"PASS"},
            {"id":"many","revision":2,"owner":"test","risk":"LOW","cardinality":"many:1-2",
             "rawCandidateCount":2,"verifiedCandidateCount":2,"deduplicatedCandidateCount":2,
             "uniqueCandidateCount":2,"verifierErrorCount":0,"status":"PASS"}
          ]
        }
    """.trimIndent()).asJsonObject

    private fun JsonObject.target(index: Int): JsonObject = getAsJsonArray("targets")[index].asJsonObject

    private fun validate(report: JsonObject): HostContractReportValidation = validate(report.toString())

    private fun validate(text: String): HostContractReportValidation = HostContractReportValidator.validate(
        text, "7.9.470", "79470", apkHash, "current-scan", catalog,
    )
}
