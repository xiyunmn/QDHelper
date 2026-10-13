package com.xiyunmn.qdhelper.host.resolver.audit

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import dalvik.system.DexClassLoader
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import com.xiyunmn.qdhelper.foundation.android.AndroidApiSupport
import com.xiyunmn.qdhelper.host.resolver.catalog.HostTargetCatalog
import com.xiyunmn.qdhelper.host.resolver.dexkit.CandidatePipeline
import com.xiyunmn.qdhelper.host.resolver.dexkit.CandidateStatus
import com.xiyunmn.qdhelper.host.resolver.dexkit.NativeAbiSupport
import com.xiyunmn.qdhelper.host.resolver.reflection.ReflectionAccess
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod

class HostContractAuditInstrumentation : Instrumentation() {
    private var arguments = Bundle()

    override fun onCreate(arguments: Bundle) {
        this.arguments = Bundle(arguments)
        start()
    }

    override fun onStart() {
        runCatching { audit() }
            .onSuccess { output ->
                finish(Activity.RESULT_OK, Bundle().apply { putString("qdhelperHostContractReport", output.absolutePath) })
            }
            .onFailure { throwable ->
                finish(Activity.RESULT_CANCELED, Bundle().apply {
                    putString("qdhelperHostContractError", "${throwable.javaClass.name}:${throwable.message.orEmpty()}")
                })
            }
    }

    private fun audit(): File {
        val startedAt = System.currentTimeMillis()
        val apk = File(requireNotNull(arguments.getString("targetApk")) { "Missing -e targetApk" })
        val version = requireNotNull(arguments.getString("targetVersion")) { "Missing -e targetVersion" }
        val versionCode = requireNotNull(arguments.getString("targetVersionCode")) { "Missing -e targetVersionCode" }
        val scanId = requireNotNull(arguments.getString("scanId")) { "Missing -e scanId" }
        require(apk.isFile) { "Target APK is not readable: $apk" }

        AndroidApiSupport.unsupportedReason()?.let { reason -> error(reason) }
        NativeAbiSupport.unsupportedReason()?.let { reason -> error(reason) }
        loadDexKitNative()
        val optimizedDirectory = File(context.codeCacheDir, "qdhelper-host-contract").apply { mkdirs() }
        val targetClassLoader = DexClassLoader(
            apk.absolutePath,
            optimizedDirectory.absolutePath,
            null,
            context.classLoader,
        )
        val statuses = mutableListOf<CandidateStatus>()
        val report = DexKitBridge.create(apk.absolutePath).use { bridge ->
            // Cold archive annotation queries can wait on cache work scheduled to the same
            // DexKit worker. Keep a second worker available in this isolated audit process.
            bridge.setThreadNum(2)
            val targets = JSONArray()
            HostTargetCatalog.forProcess(HostSymbols.PACKAGE).forEach { spec ->
                android.util.Log.i("HostContractAudit", "start ${spec.key.id.value}")
                val target = auditTarget(bridge, targetClassLoader, spec)
                android.util.Log.i("HostContractAudit", "done ${spec.key.id.value}: ${target.getString("status")}")
                statuses += CandidateStatus.valueOf(target.getString("status"))
                targets.put(target)
            }
            val overall = overallVerdict(statuses)
            JSONObject()
                .put("schemaVersion", 3)
                .put("toolVersion", 3)
                .put("hostPackage", HostSymbols.PACKAGE)
                .put("hostVersion", version)
                .put("hostVersionCode", versionCode)
                .put("apkSha256", apk.sha256())
                .put("scanPerformed", true)
                .put("scanId", scanId)
                .put("startedAtEpochMs", startedAt)
                .put("catalogHash", HostTargetCatalog.sha256(HostSymbols.PACKAGE))
                .put("verdict", overall)
                .put("overallVerdict", overall)
                .put("targets", targets)
        }
        report.put("durationMs", System.currentTimeMillis() - startedAt)

        return File(context.cacheDir, "host-contract-$version.json").apply {
            writeText(report.toString(2), Charsets.UTF_8)
            check(isFile && length() > 0L)
        }
    }

    private fun auditTarget(
        bridge: DexKitBridge,
        classLoader: ClassLoader,
        spec: com.xiyunmn.qdhelper.host.resolver.catalog.HostTargetSpec,
    ): JSONObject {
        return runCatching {
            val raw = bridge.findMethod(FindMethod.create().apply(spec.query.query))
            var materializeErrors = 0
            val methods = raw.mapNotNull { data ->
                runCatching {
                    ReflectionAccess.accessible(data.getMethodInstance(classLoader))
                }.getOrElse {
                    materializeErrors += 1
                    null
                }
            }
            val result = CandidatePipeline.evaluate(
                spec = spec,
                rawMethods = methods,
                rawCandidateCount = raw.size,
                initialVerifierErrorCount = materializeErrors,
            )
            JSONObject()
                .put("id", spec.key.id.value)
                .put("revision", spec.revision)
                .put("owner", spec.owner.value)
                .put("risk", spec.risk.name)
                .put("cardinality", result.cardinality)
                .put("rawCandidateCount", result.rawCandidateCount)
                .put("verifiedCandidateCount", result.verifiedCandidateCount)
                .put("deduplicatedCandidateCount", result.deduplicatedCandidateCount)
                .put("uniqueCandidateCount", result.deduplicatedCandidateCount)
                .put("verifierErrorCount", result.verifierErrorCount)
                .put("status", result.status.name)
                .put("rawDescriptors", JSONArray(raw.map { it.descriptor }))
                .put("resolvedMethods", JSONArray(result.methods.map { it.toGenericString() }))
        }.getOrElse { throwable ->
            JSONObject()
                .put("id", spec.key.id.value)
                .put("revision", spec.revision)
                .put("owner", spec.owner.value)
                .put("risk", spec.risk.name)
                .put("cardinality", CandidatePipeline.cardinalityLabel(spec.cardinality))
                .put("rawCandidateCount", 0)
                .put("verifiedCandidateCount", 0)
                .put("deduplicatedCandidateCount", 0)
                .put("uniqueCandidateCount", 0)
                .put("verifierErrorCount", 1)
                .put("status", CandidateStatus.VERIFIER_ERROR.name)
                .put("error", "${throwable.javaClass.name}:${throwable.message.orEmpty().take(200)}")
        }
    }

    private fun overallVerdict(statuses: List<CandidateStatus>): String = when {
        statuses.isNotEmpty() && statuses.all { it == CandidateStatus.PASS } -> "PASS"
        statuses.any { it == CandidateStatus.PASS } -> "PARTIAL"
        else -> "FAIL"
    }

    private fun loadDexKitNative() {
        runCatching {
            System.loadLibrary("dexkit")
        }.recoverCatching {
            val appInfo = context.packageManager.getApplicationInfo(context.packageName, 0)
            System.load(File(appInfo.nativeLibraryDir, System.mapLibraryName("dexkit")).absolutePath)
        }.getOrElse { throwable ->
            error("native_load_failed:${throwable.javaClass.simpleName}")
        }
    }

}

private fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}
