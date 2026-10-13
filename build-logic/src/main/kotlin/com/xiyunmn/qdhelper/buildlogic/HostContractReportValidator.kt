package com.xiyunmn.qdhelper.buildlogic

import com.google.gson.JsonObject
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonPrimitive
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.math.BigDecimal
import java.util.Properties

internal data class ExpectedHostTarget(val revision: Int, val cardinality: String, val owner: String, val risk: String)

internal data class ExpectedHostCatalog(val hash: String, val targets: Map<String, ExpectedHostTarget>) {
    init {
        require(hash.matches(Regex("[0-9a-f]{64}"))) { "Invalid expected catalog hash" }
        require(targets.isNotEmpty()) { "Expected catalog is empty" }
    }

    companion object {
        fun from(properties: Properties): ExpectedHostCatalog {
            require(properties.getProperty("schemaVersion") == "3") { "Unsupported catalog manifest schema" }
            val targets = properties.stringPropertyNames()
                .filter { it.startsWith("target.") && it.endsWith(".revision") }
                .associate { key ->
                    val id = key.removePrefix("target.").removeSuffix(".revision")
                    val revision = requireNotNull(properties.getProperty(key).toIntOrNull()).also { require(it > 0) }
                    id to ExpectedHostTarget(
                        revision,
                        requireNotNull(properties.getProperty("target.$id.cardinality")),
                        requireNotNull(properties.getProperty("target.$id.owner")),
                        requireNotNull(properties.getProperty("target.$id.risk")),
                    )
                }
            return ExpectedHostCatalog(requireNotNull(properties.getProperty("catalogHash")), targets)
        }
    }
}

internal data class HostContractReportValidation(val catalogHash: String, val verdict: String, val verifierErrors: Long)

internal object HostContractReportValidator {
    fun validate(
        text: String,
        expectedVersion: String,
        expectedVersionCode: String,
        expectedApkSha256: String,
        expectedScanId: String,
        expectedCatalog: ExpectedHostCatalog,
    ): HostContractReportValidation {
        require(text.length <= 4 * 1024 * 1024) { "Audit report is too large" }
        val report = JsonReader(StringReader(text)).use { reader ->
            reader.strictness = Strictness.STRICT
            val value = readValue(reader, 0)
            require(reader.peek() == JsonToken.END_DOCUMENT && value.isJsonObject) { "Expected one complete audit JSON object" }
            value.asJsonObject
        }
        require(report.number("schemaVersion") == 3L && report.number("toolVersion") == 3L) { "Unsupported audit schema/tool" }
        require(report.string("hostPackage") == "com.qidian.QDReader") { "Audit package mismatch" }
        require(report.string("hostVersion") == expectedVersion) { "Audit host version mismatch" }
        require(report.string("hostVersionCode") == expectedVersionCode) { "Audit host version code mismatch" }
        require(report.string("apkSha256") == expectedApkSha256) { "Audit APK hash mismatch" }
        require(report.string("scanId") == expectedScanId) { "Stale or unrelated audit scan" }
        require(report.number("startedAtEpochMs") > 0 && report.number("durationMs") >= 0) { "Invalid audit evidence time" }
        require(report.get("scanPerformed")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && it.asBoolean } == true) {
            "NO_SCAN and raw-only reports are not contract evidence"
        }
        val catalogHash = report.string("catalogHash")
        require(catalogHash == expectedCatalog.hash) { "Audit catalog mismatch" }
        val targets = requireNotNull(report.get("targets")?.takeIf { it.isJsonArray }?.asJsonArray) { "Missing audit targets" }
        val seen = HashSet<String>()
        var verifierErrors = 0L
        val statuses = targets.map { element ->
            require(element.isJsonObject) { "Invalid audit target" }
            val target = element.asJsonObject
            val id = target.string("id")
            require(seen.add(id)) { "Duplicate audit target: $id" }
            val expected = requireNotNull(expectedCatalog.targets[id]) { "Unexpected audit target: $id" }
            require(target.number("revision") == expected.revision.toLong()) { "Audit revision mismatch: $id" }
            require(target.string("cardinality") == expected.cardinality) { "Audit cardinality mismatch: $id" }
            require(target.string("owner") == expected.owner && target.string("risk") == expected.risk) { "Audit owner/risk mismatch: $id" }
            val raw = target.number("rawCandidateCount")
            val verified = target.number("verifiedCandidateCount")
            val unique = target.number("deduplicatedCandidateCount")
            val errors = target.number("verifierErrorCount")
            require(raw in 0..Int.MAX_VALUE.toLong() && raw >= verified && verified >= unique && unique >= 0 &&
                errors in 0..Int.MAX_VALUE.toLong()
            ) { "Invalid audit counts: $id" }
            require(target.number("uniqueCandidateCount") == unique) { "Conflicting unique counts: $id" }
            require(verified <= raw - errors || raw == 0L && errors == 1L && target.string("error").isNotBlank()) {
                "Invalid verifier error count: $id"
            }
            val status = when {
                unique == 0L -> if (errors > 0L) "VERIFIER_ERROR" else "UNRESOLVED"
                !matchesCardinality(expected.cardinality, unique) -> "AMBIGUOUS"
                else -> "PASS"
            }
            require(target.string("status") == status) { "Audit status disagrees with candidates: $id" }
            verifierErrors += errors
            status
        }
        require(seen == expectedCatalog.targets.keys) { "Audit target set is incomplete" }
        val verdict = when {
            statuses.all { it == "PASS" } -> "PASS"
            statuses.any { it == "PASS" } -> "PARTIAL"
            else -> "FAIL"
        }
        require(report.string("verdict") == verdict && report.string("overallVerdict") == verdict) { "Audit overall verdict is inconsistent" }
        return HostContractReportValidation(catalogHash, verdict, verifierErrors)
    }

    private fun readValue(reader: JsonReader, depth: Int): JsonElement {
        require(depth <= 16) { "Audit JSON nesting is too deep" }
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> JsonObject().apply {
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    require(!has(name)) { "Duplicate audit JSON field: $name" }
                    add(name, readValue(reader, depth + 1))
                }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> JsonArray().apply {
                reader.beginArray()
                while (reader.hasNext()) add(readValue(reader, depth + 1))
                reader.endArray()
            }
            JsonToken.STRING -> JsonPrimitive(reader.nextString())
            JsonToken.NUMBER -> JsonPrimitive(BigDecimal(reader.nextString()))
            JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
            JsonToken.NULL -> {
                reader.nextNull()
                JsonNull.INSTANCE
            }
            else -> error("Invalid audit JSON value")
        }
    }

    private fun matchesCardinality(label: String, count: Long): Boolean {
        if (label == "one") return count == 1L
        val bounds = requireNotNull(Regex("many:(\\d+)-(\\d+)").matchEntire(label)) { "Invalid catalog cardinality" }
        val minimum = bounds.groupValues[1].toLong()
        val maximum = bounds.groupValues[2].toLong()
        require(maximum >= minimum)
        return count in minimum..maximum
    }

    private fun JsonObject.string(name: String): String {
        val value = requireNotNull(get(name)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive) { "Missing string: $name" }
        require(value.isString) { "Expected string: $name" }
        return value.asString
    }

    private fun JsonObject.number(name: String): Long {
        val value = requireNotNull(get(name)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive) { "Missing number: $name" }
        require(value.isNumber) { "Expected number: $name" }
        return value.asBigDecimal.longValueExact()
    }
}
