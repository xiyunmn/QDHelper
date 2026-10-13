package com.xiyunmn.qdhelper.feature.diagnostics.cookie

import android.content.Context
import android.os.Process
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols as QDReaderHost
import org.json.JSONObject
import java.lang.reflect.Modifier
import java.security.KeyStore
import java.security.cert.X509Certificate

/**
 * Read-only view of the certificate already created by Tianyu/TuringFD.
 *
 * TuringFD uses the Android Key Attestation alias `dddd<uid>`. We deliberately do not invoke its
 * key-generation entry point here: opening this panel must not create a key or initiate a risk
 * request. If the host has no existing alias, the result is explicitly reported as unavailable.
 */
object TianyuAttestationDiagnostics {
    private const val ATTESTATION_EXTENSION_OID = "1.3.6.1.4.1.11129.2.1.17"
    private const val ROOT_OF_TRUST_TAG = 704

    data class ExtensionEntry(
        val name: String,
        val value: String,
        val sensitive: Boolean,
    )

    data class Info(
        val available: Boolean,
        val certificateCount: Int = 0,
        val certificateChainValid: Boolean? = null,
        val extensionOid: String? = null,
        val extensionEntries: List<ExtensionEntry> = emptyList(),
        val attestationVersion: Long? = null,
        val attestationSecurityLevel: Int? = null,
        val keymasterVersion: Long? = null,
        val keymasterSecurityLevel: Int? = null,
        val challengePresent: Boolean? = null,
        val uniqueIdPresent: Boolean? = null,
        val deviceLocked: Boolean? = null,
        val verifiedBootState: Int? = null,
        val verifiedBootKeyPresent: Boolean? = null,
        val verifiedBootHashPresent: Boolean? = null,
        val osVersion: Long? = null,
        val osPatchLevel: Long? = null,
        val vendorPatchLevel: Long? = null,
        val bootPatchLevel: Long? = null,
        val attestationApplicationIdPresent: Boolean? = null,
        val error: String? = null,
    ) {
        fun toText(maskSensitive: Boolean = true): String = buildString {
            append("—— 天御 TuringFD / Key Attestation ——\n")
            if (!available) {
                append("结果不可用：")
                append(error ?: "未找到宿主现有 attestation 证书")
                append("\n未主动生成密钥或发起风险请求")
                return@buildString
            }
            append("证书链长度: ")
            append(certificateCount)
            append("\n证书链签名自洽: ")
            append(certificateChainValid.yesNoUnknown())
            append("\n证书扩展 OID: ")
            append(extensionOid ?: "未读取")
            append("\n证书扩展字段:")
            if (extensionEntries.isEmpty()) {
                append(" 未解析")
            } else {
                extensionEntries.forEach { entry ->
                    append("\n  ")
                    append(entry.name)
                    append(": ")
                    append(if (entry.sensitive && maskSensitive) "<redacted>" else entry.value)
                }
            }
            append("\nattestationVersion: ")
            append(attestationVersion ?: "未解析")
            append("\nattestationSecurityLevel: ")
            append(attestationSecurityLevel?.let(::securityLevelLabel) ?: "未解析")
            append("\nkeymasterVersion: ")
            append(keymasterVersion ?: "未解析")
            append("\nkeymasterSecurityLevel: ")
            append(keymasterSecurityLevel?.let(::securityLevelLabel) ?: "未解析")
            append("\nattestationChallenge: ")
            append(challengePresent.presentUnknown())
            append("\nuniqueId: ")
            append(uniqueIdPresent.presentUnknown())
            append("\ndeviceLocked: ")
            append(deviceLocked?.let { if (it) "是" else "否" } ?: "未解析")
            append("\nverifiedBootState: ")
            append(verifiedBootState?.let(::verifiedBootStateLabel) ?: "未解析")
            append("\nverifiedBootKey: ")
            append(verifiedBootKeyPresent.presentUnknown())
            append("\nverifiedBootHash: ")
            append(verifiedBootHashPresent.presentUnknown())
            append("\nosVersion: ")
            append(osVersion?.let(::osVersionLabel) ?: "未解析")
            append("\nosPatchLevel: ")
            append(osPatchLevel?.let(::patchLevelLabel) ?: "未解析")
            append("\nvendorPatchLevel: ")
            append(vendorPatchLevel?.let(::patchLevelLabel) ?: "未解析")
            append("\nbootPatchLevel: ")
            append(bootPatchLevel?.let(::patchLevelLabel) ?: "未解析")
            append("\nattestationApplicationId: ")
            append(attestationApplicationIdPresent.presentUnknown())
            append("\n说明：这是 Android Key Attestation 证书链结果，与宿主 nib 的 getprop 检测分开。")
        }

        private fun Boolean?.yesNoUnknown(): String = when (this) {
            true -> "是"
            false -> "否"
            null -> "未校验"
        }

        private fun Boolean?.presentUnknown(): String = when (this) {
            true -> "存在（内容不显示）"
            false -> "不存在"
            null -> "未解析"
        }

        companion object {
            fun unavailable(error: String? = null): Info = Info(available = false, error = error)
        }
    }

    fun read(context: Context): Info {
        return runCatching {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            val alias = "dddd${Process.myUid()}"
            val chain = keyStore.getCertificateChain(alias)
                ?: return unavailable("未找到宿主 TuringFD 证书（alias=$alias）")
            val leaf = chain.firstOrNull() as? X509Certificate
                ?: return unavailable("宿主 TuringFD 证书链为空")
            val extensions = findExtensions(context, leaf)
            if (extensions.isEmpty()) {
                return unavailable("证书不含天御或 Android Key Attestation 扩展")
            }
            val parsed = extensions.firstNotNullOfOrNull { extension -> parseAttestation(extension.bytes) }
            val extensionEntries = extensions.flatMap { extension ->
                parseJsonEntries(extension.bytes).map { entry ->
                    if (extensions.size == 1) entry else entry.copy(name = "${extension.oid}/${entry.name}")
                }
            }
            if (parsed == null && extensionEntries.isEmpty()) {
                return unavailable("无法解析证书扩展")
            }
            Info(
                available = true,
                certificateCount = chain.size,
                certificateChainValid = verifyChain(chain.filterIsInstance<X509Certificate>()),
                extensionOid = extensions.joinToString { it.oid },
                extensionEntries = extensionEntries,
                attestationVersion = parsed?.attestationVersion,
                attestationSecurityLevel = parsed?.attestationSecurityLevel,
                keymasterVersion = parsed?.keymasterVersion,
                keymasterSecurityLevel = parsed?.keymasterSecurityLevel,
                challengePresent = parsed?.challengePresent,
                uniqueIdPresent = parsed?.uniqueIdPresent,
                deviceLocked = parsed?.rootOfTrust?.deviceLocked,
                verifiedBootState = parsed?.rootOfTrust?.verifiedBootState,
                verifiedBootKeyPresent = parsed?.rootOfTrust?.verifiedBootKeyPresent,
                verifiedBootHashPresent = parsed?.rootOfTrust?.verifiedBootHashPresent,
                osVersion = parsed?.osVersion,
                osPatchLevel = parsed?.osPatchLevel,
                vendorPatchLevel = parsed?.vendorPatchLevel,
                bootPatchLevel = parsed?.bootPatchLevel,
                attestationApplicationIdPresent = parsed?.attestationApplicationIdPresent,
            )
        }.getOrElse { throwable ->
            unavailable("${throwable.javaClass.simpleName}: ${throwable.message.orEmpty()}".trimEnd(':', ' '))
        }
    }

    internal fun verifiedBootStateLabel(value: Int): String = when (value) {
        0 -> "Verified（0）"
        1 -> "SelfSigned（1）"
        2 -> "Unverified（2）"
        3 -> "Failed（3）"
        else -> "未知（$value）"
    }

    internal fun securityLevelLabel(value: Int): String = when (value) {
        0 -> "Software（0）"
        1 -> "TEE（1）"
        2 -> "StrongBox（2）"
        else -> "未知（$value）"
    }

    internal fun osVersionLabel(value: Long): String {
        val major = value / 10_000
        val minor = value / 100 % 100
        val patch = value % 100
        return "$major.$minor.$patch（$value）"
    }

    internal fun patchLevelLabel(value: Long): String {
        val raw = value.toString()
        return when (raw.length) {
            6 -> "${raw.take(4)}-${raw.substring(4, 6)}（$value）"
            8 -> "${raw.take(4)}-${raw.substring(4, 6)}-${raw.takeLast(2)}（$value）"
            else -> value.toString()
        }
    }

    private data class Attestation(
        val attestationVersion: Long?,
        val attestationSecurityLevel: Int?,
        val keymasterVersion: Long?,
        val keymasterSecurityLevel: Int?,
        val challengePresent: Boolean,
        val uniqueIdPresent: Boolean,
        val rootOfTrust: RootOfTrust?,
        val osVersion: Long?,
        val osPatchLevel: Long?,
        val vendorPatchLevel: Long?,
        val bootPatchLevel: Long?,
        val attestationApplicationIdPresent: Boolean,
    )

    private data class CertificateExtension(val oid: String, val bytes: ByteArray)

    private data class RootOfTrust(
        val deviceLocked: Boolean?,
        val verifiedBootState: Int?,
        val verifiedBootKeyPresent: Boolean,
        val verifiedBootHashPresent: Boolean,
    )

    private data class Tlv(
        val tagClass: Int,
        val tagNumber: Int,
        val constructed: Boolean,
        val contentStart: Int,
        val contentEnd: Int,
    )

    private fun findExtensions(context: Context, certificate: X509Certificate): List<CertificateExtension> {
        val turingOid = runCatching {
            val loader = context.classLoader ?: return@runCatching null
            val parser = FeatureHooks.findClassOrNull(
                QDReaderHost.Classes.TURING_CERT_EXTENSION_PARSER,
                loader,
            ) ?: return@runCatching null
            FeatureHooks.allDeclaredFields(parser)
                .asSequence()
                .filter { field -> Modifier.isStatic(field.modifiers) && field.type == String::class.java }
                .mapNotNull { field -> FeatureHooks.read(field, null) as? String }
                .firstOrNull(::looksLikeOid)
        }.getOrNull()
        return listOfNotNull(turingOid, ATTESTATION_EXTENSION_OID)
            .distinct()
            .mapNotNull { oid ->
                certificate.getExtensionValue(oid)?.let { bytes -> CertificateExtension(oid, bytes) }
            }
    }

    private fun looksLikeOid(value: String): Boolean =
        value.length in 5..80 && value.split('.').all { part -> part.toLongOrNull() != null }

    private fun parseJsonEntries(extension: ByteArray): List<ExtensionEntry> {
        val start = extension.indexOf('{'.code.toByte())
        val end = extension.indexOfLast { it == '}'.code.toByte() }
        if (start < 0 || end <= start) return emptyList()
        return runCatching {
            val json = JSONObject(String(extension, start, end - start + 1, Charsets.UTF_8))
            json.keys().asSequence().toList().sorted().map { key ->
                val value = json.opt(key)
                ExtensionEntry(
                    name = key,
                    value = when (value) {
                        null, JSONObject.NULL -> "null"
                        else -> value.toString()
                    },
                    sensitive = value is String,
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun parseAttestation(encodedExtension: ByteArray): Attestation? {
        // X509Certificate#getExtensionValue returns the extension wrapped in an
        // outer DER OCTET STRING. The attestation payload inside that wrapper is
        // itself normally a DER SEQUENCE (some providers add one more OCTET
        // wrapper), so unwrap those containers before walking AuthorizationList.
        var bytes = encodedExtension
        repeat(2) {
            val wrapper = parse(bytes, 0) ?: return@repeat
            if (wrapper.tagClass != 0 || wrapper.tagNumber != 4) return@repeat
            bytes = bytes.copyOfRange(wrapper.contentStart, wrapper.contentEnd)
        }
        val outer = parse(bytes, 0) ?: return null
        val fields = children(bytes, outer).toList()
        if (fields.size < 8) return null
        val softwareEnforced = fields[6]
        val teeEnforced = fields[7]
        return Attestation(
            attestationVersion = readUniversalNumber(bytes, fields.getOrNull(0)),
            attestationSecurityLevel = readUniversalNumber(bytes, fields.getOrNull(1))?.toInt(),
            keymasterVersion = readUniversalNumber(bytes, fields.getOrNull(2)),
            keymasterSecurityLevel = readUniversalNumber(bytes, fields.getOrNull(3))?.toInt(),
            challengePresent = fields.getOrNull(4)?.let { it.contentEnd > it.contentStart } == true,
            uniqueIdPresent = fields.getOrNull(5)?.let { it.contentEnd > it.contentStart } == true,
            rootOfTrust = findRoot(bytes, teeEnforced) ?: findRoot(bytes, softwareEnforced),
            osVersion = findAuthorizationNumber(bytes, teeEnforced, 705)
                ?: findAuthorizationNumber(bytes, softwareEnforced, 705),
            osPatchLevel = findAuthorizationNumber(bytes, teeEnforced, 706)
                ?: findAuthorizationNumber(bytes, softwareEnforced, 706),
            vendorPatchLevel = findAuthorizationNumber(bytes, teeEnforced, 718)
                ?: findAuthorizationNumber(bytes, softwareEnforced, 718),
            bootPatchLevel = findAuthorizationNumber(bytes, teeEnforced, 719)
                ?: findAuthorizationNumber(bytes, softwareEnforced, 719),
            attestationApplicationIdPresent = findAuthorizationTag(bytes, softwareEnforced, 709) != null ||
                findAuthorizationTag(bytes, teeEnforced, 709) != null,
        )
    }

    private fun findRoot(bytes: ByteArray, node: Tlv): RootOfTrust? {
        if (node.tagClass == 0x80 && node.tagNumber == ROOT_OF_TRUST_TAG) {
            val primitives = descendants(bytes, node)
                .filter { it.tagClass == 0 }
                .toList()
            val locked = primitives.firstOrNull { it.tagClass == 0 && it.tagNumber == 1 }
                ?.let { bytes[it.contentStart].toInt() != 0 }
            val state = primitives.firstOrNull {
                it.tagClass == 0 && (it.tagNumber == 10 || it.tagNumber == 2)
            }?.let { readUnsignedLong(bytes, it.contentStart, it.contentEnd).toInt() }
            val octetStrings = primitives.filter { it.tagNumber == 4 }
            return RootOfTrust(
                deviceLocked = locked,
                verifiedBootState = state,
                verifiedBootKeyPresent = octetStrings.getOrNull(0)?.let { it.contentEnd > it.contentStart } == true,
                verifiedBootHashPresent = octetStrings.getOrNull(1)?.let { it.contentEnd > it.contentStart } == true,
            )
        }
        if (!node.constructed) return null
        return children(bytes, node).firstNotNullOfOrNull { findRoot(bytes, it) }
    }

    private fun findAuthorizationNumber(bytes: ByteArray, node: Tlv, tag: Int): Long? {
        val tagged = findAuthorizationTag(bytes, node, tag) ?: return null
        val primitive = descendants(bytes, tagged).firstOrNull { child ->
            child.tagClass == 0 && (child.tagNumber == 2 || child.tagNumber == 10)
        } ?: return null
        return readUnsignedLong(bytes, primitive.contentStart, primitive.contentEnd)
    }

    private fun findAuthorizationTag(bytes: ByteArray, node: Tlv, tag: Int): Tlv? {
        if (node.tagClass == 0x80 && node.tagNumber == tag) return node
        if (!node.constructed) return null
        return children(bytes, node).firstNotNullOfOrNull { child -> findAuthorizationTag(bytes, child, tag) }
    }

    private fun readUniversalNumber(bytes: ByteArray, node: Tlv?): Long? {
        node ?: return null
        if (node.tagClass != 0 || (node.tagNumber != 2 && node.tagNumber != 10)) return null
        return readUnsignedLong(bytes, node.contentStart, node.contentEnd)
    }

    private fun verifyChain(chain: List<X509Certificate>): Boolean? {
        if (chain.isEmpty()) return null
        return runCatching {
            chain.zipWithNext().forEach { (certificate, issuer) ->
                certificate.verify(issuer.publicKey)
            }
            true
        }.getOrDefault(false)
    }

    private fun descendants(bytes: ByteArray, node: Tlv): Sequence<Tlv> = sequence {
        for (child in children(bytes, node)) {
            yield(child)
            if (child.constructed) yieldAll(descendants(bytes, child))
        }
    }

    private fun children(bytes: ByteArray, parent: Tlv): Sequence<Tlv> = sequence {
        var offset = parent.contentStart
        while (offset < parent.contentEnd) {
            val child = parse(bytes, offset) ?: break
            if (child.contentEnd > parent.contentEnd) break
            yield(child)
            offset = child.contentEnd
        }
    }

    private fun parse(bytes: ByteArray, offset: Int): Tlv? {
        if (offset >= bytes.size) return null
        val first = bytes[offset].toInt() and 0xff
        val tagClass = first and 0xc0
        val constructed = (first and 0x20) != 0
        var tagNumber = first and 0x1f
        var cursor = offset + 1
        if (tagNumber == 0x1f) {
            tagNumber = 0
            var count = 0
            while (cursor < bytes.size) {
                val part = bytes[cursor++].toInt() and 0xff
                tagNumber = (tagNumber shl 7) or (part and 0x7f)
                count++
                if (part and 0x80 == 0) break
                if (count > 5) return null
            }
        }
        if (cursor >= bytes.size) return null
        val lengthByte = bytes[cursor++].toInt() and 0xff
        val length = when {
            lengthByte and 0x80 == 0 -> lengthByte
            lengthByte == 0x80 -> return null
            else -> {
                val count = lengthByte and 0x7f
                if (count == 0 || count > 4 || cursor + count > bytes.size) return null
                var result = 0
                repeat(count) { result = (result shl 8) or (bytes[cursor++].toInt() and 0xff) }
                result
            }
        }
        val end = cursor + length
        if (end > bytes.size) return null
        return Tlv(tagClass, tagNumber, constructed, cursor, end)
    }

    private fun readUnsignedLong(bytes: ByteArray, start: Int, end: Int): Long {
        var value = 0L
        for (index in start until end) {
            value = (value shl 8) or (bytes[index].toInt() and 0xff).toLong()
        }
        return value
    }

    private fun unavailable(error: String): Info = Info.unavailable(error)
}
