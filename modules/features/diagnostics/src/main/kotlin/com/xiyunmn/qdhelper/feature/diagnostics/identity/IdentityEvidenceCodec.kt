package com.xiyunmn.qdhelper.feature.diagnostics.identity

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

internal object IdentityEvidenceCodec {
    private const val DIGEST_PREFIX_LENGTH = 12
    private const val QDINFO_MIN_FIELDS = 19

    fun fingerprint(value: String?): String {
        if (value.isNullOrEmpty()) return "empty"
        return "len=${value.length},sha256=${sha256Prefix(value.toByteArray(Charsets.UTF_8))}"
    }

    fun safeFlag(value: String?): String {
        val normalized = value?.trim()?.lowercase().orEmpty()
        return if (normalized in SAFE_FLAG_VALUES) normalized.ifEmpty { "empty" } else fingerprint(value)
    }

    fun parseQdInfo(payload: String?, qimeiArgument: String?): QdInfoSummary? {
        if (payload.isNullOrEmpty()) return null
        val fields = payload.split('|')
        if (fields.size < QDINFO_MIN_FIELDS) return null
        val timestamp = fields.getOrNull(12)?.toLongOrNull()
        if (timestamp == null || timestamp < 1_000_000_000_000L) return null

        val cyid = fields.getOrNull(0)
        val qimei16 = fields.getOrNull(14)
        val ssaid = fields.getOrNull(15)
        val cyidSource = when {
            cyid.isNullOrEmpty() -> "empty"
            cyid == qimei16 -> "qimei16"
            cyid == ssaid -> "ssaid"
            else -> "other"
        }
        return QdInfoSummary(
            fieldCount = fields.size,
            cyid = fingerprint(cyid),
            userId = fingerprint(fields.getOrNull(11)),
            timestamp = timestamp,
            qimei16 = fingerprint(qimei16),
            ssaid = fingerprint(ssaid),
            oaid = fingerprint(fields.getOrNull(17)),
            qimei36 = fingerprint(fields.getOrNull(18)),
            qimeiArgumentMatches = qimeiArgument?.let { it == qimei16 },
            cyidSource = cyidSource,
        )
    }

    fun fileFingerprint(file: File): String {
        if (!file.isFile) return "missing"
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return "size=${file.length()},mtime=${file.lastModified()},sha256=${hexPrefix(digest.digest())}"
    }

    private fun sha256Prefix(bytes: ByteArray): String =
        hexPrefix(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun hexPrefix(digest: ByteArray): String =
        digest.joinToString("") { byte -> "%02x".format(byte) }.take(DIGEST_PREFIX_LENGTH)

    private val SAFE_FLAG_VALUES = setOf("", "0", "1", "true", "false", "yes", "no", "m", "f")
}

internal data class QdInfoSummary(
    val fieldCount: Int,
    val cyid: String,
    val userId: String,
    val timestamp: Long,
    val qimei16: String,
    val ssaid: String,
    val oaid: String,
    val qimei36: String,
    val qimeiArgumentMatches: Boolean?,
    val cyidSource: String,
) {
    fun asLogString(): String =
        "fields=$fieldCount,cyidFp=[$cyid],cyidSource=$cyidSource,uidFp=[$userId]," +
            "ts=$timestamp,q16Fp=[$qimei16],ssaidFp=[$ssaid],oidFp=[$oaid],q36Fp=[$qimei36]," +
            "argQimeiMatch=${qimeiArgumentMatches ?: "unknown"}"
}
