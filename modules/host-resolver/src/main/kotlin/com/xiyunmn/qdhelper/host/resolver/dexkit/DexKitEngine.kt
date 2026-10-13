package com.xiyunmn.qdhelper.host.resolver.dexkit

import android.content.Context
import android.os.Looper
import com.xiyunmn.qdhelper.foundation.android.AndroidApiSupport
import com.xiyunmn.qdhelper.hook.concurrency.ManagedExecutor
import com.xiyunmn.qdhelper.host.resolver.BuildConfig
import com.xiyunmn.qdhelper.host.resolver.catalog.HostTargetSpec
import com.xiyunmn.qdhelper.host.resolver.environment.ResolverEnvironment
import com.xiyunmn.qdhelper.host.resolver.reflection.ReflectionAccess
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import java.util.concurrent.Future
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Owns the single DexKit worker, ClassLoader-bound session, and hot-reload generation. */
internal object DexKitEngine {
    const val VERSION = BuildConfig.DEXKIT_VERSION

    private val stateLock = Any()
    private val generationCounter = AtomicLong(0)

    @Volatile
    private var activeSession: Session? = null

    @Volatile
    private var nativeLoadAttempted = false

    @Volatile
    private var nativeLoadSucceeded = false

    @Volatile
    private var nativeFailureReason: String? = null

    private val unverifiedApiWarningLogged = AtomicBoolean(false)

    val generation: Long get() = generationCounter.get()

    fun scan(
        context: Context,
        classLoader: ClassLoader,
        spec: HostTargetSpec,
    ): DexKitScanResult {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return DexKitScanResult.Unavailable("main_thread_scan_forbidden")
        }
        nativeUnavailableReason(context)?.let { reason ->
            return DexKitScanResult.Unavailable(reason)
        }
        val session = session(classLoader)
        val token = session.generation
        return runCatching {
            session.worker.submit {
                if (!isCurrent(token)) {
                    DexKitScanResult.Unavailable("stale_generation")
                } else {
                    scanResult(context, session, spec)
                }
            }.get()
        }.getOrElse { throwable ->
            scanFailure(spec, throwable)
        }
    }

    fun submitBatch(
        context: Context,
        classLoader: ClassLoader,
        block: (generation: Long, scanner: (HostTargetSpec) -> DexKitScanResult) -> Unit,
    ): Future<*> {
        val session = session(classLoader)
        val token = session.generation
        return session.worker.submit {
            if (!isCurrent(token)) return@submit
            block(token) { spec ->
                if (isCurrent(token)) scanResult(context, session, spec)
                else DexKitScanResult.Unavailable("stale_generation")
            }
        }
    }

    fun isCurrent(token: Long): Boolean {
        val session = activeSession ?: return false
        return token == generation && token == session.generation && !session.worker.isClosed
    }

    fun nativeState(): String = when {
        nativeLoadSucceeded -> "loaded"
        nativeFailureReason != null -> "load_failed"
        nativeLoadAttempted -> "load_failed"
        else -> "not_attempted"
    }

    /** Performs ABI/native preflight before any DexKit bridge is created. */
    fun nativeUnavailableReason(context: Context?): String? {
        val apiStatus = AndroidApiSupport.status()
        if (apiStatus == AndroidApiSupport.Status.UNVERIFIED &&
            unverifiedApiWarningLogged.compareAndSet(false, true)
        ) {
            ResolverEnvironment.warn(
                "DexKit running on an unverified Android API: ${AndroidApiSupport.summary()}",
            )
        }
        AndroidApiSupport.unsupportedReason()?.let { reason ->
            nativeFailureReason = reason
            return reason
        }
        NativeAbiSupport.unsupportedReason()?.let { reason ->
            nativeFailureReason = reason
            return reason
        }
        if (context == null) return "context_missing"
        return if (ensureNativeLoaded(context)) null else nativeFailureReason ?: NativeAbiSupport.NATIVE_LOAD_FAILED
    }

    fun close() {
        val closing = synchronized(stateLock) {
            generationCounter.incrementAndGet()
            activeSession.also { activeSession = null }
        }
        closing?.worker?.close()
    }

    private fun scanResult(
        context: Context,
        session: Session,
        spec: HostTargetSpec,
    ): DexKitScanResult = runCatching {
        val result = scanOnWorker(context, session, spec)
        if (isCurrent(session.generation)) DexKitScanResult.Candidates(result, session.generation)
        else DexKitScanResult.Unavailable("stale_generation")
    }.getOrElse { scanFailure(spec, it) }

    private fun scanFailure(spec: HostTargetSpec, throwable: Throwable): DexKitScanResult.Unavailable {
        val failure = (throwable as? ExecutionException)?.cause ?: throwable
        if (failure is CancellationException || failure is InterruptedException) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            return DexKitScanResult.Unavailable("stale_generation")
        }
        ResolverEnvironment.warn(
            "DexKit scan failed: target=${spec.key.id.value}, error=${failure.javaClass.name}",
        )
        return DexKitScanResult.Unavailable(
            (failure as? NativeUnavailableException)?.reason
                ?: "scan_exception:${failure.javaClass.simpleName}",
        )
    }

    private fun scanOnWorker(
        context: Context,
        session: Session,
        spec: HostTargetSpec,
    ): CandidatePipelineResult {
        val startedAt = System.currentTimeMillis()
        val resultData = bridgeFor(context, session)
            .findMethod(FindMethod.create().apply(spec.query.query))
        var materializeErrors = 0
        val raw = resultData.mapNotNull { data ->
            runCatching {
                ReflectionAccess.accessible(data.getMethodInstance(session.classLoader))
            }.getOrElse {
                materializeErrors += 1
                null
            }
        }
        val result = CandidatePipeline.evaluate(
            spec = spec,
            rawMethods = raw,
            rawCandidateCount = resultData.size,
            initialVerifierErrorCount = materializeErrors,
        )
        if (result.deduplicatedCandidateCount != result.verifiedCandidateCount) {
            ResolverEnvironment.warn(
                "DexKit duplicate descriptors normalized: target=${spec.key.id.value}, " +
                    "duplicates=${result.verifiedCandidateCount - result.deduplicatedCandidateCount}",
            )
        }
        if (result.verifierErrorCount > 0) {
            ResolverEnvironment.warn(
                "DexKit verifier errors: target=${spec.key.id.value}, count=${result.verifierErrorCount}",
            )
        }
        if (ResolverEnvironment.isCandidateLogEnabled(context)) {
            ResolverEnvironment.info(
                "DexKit candidates: target=${spec.key.id.value}, raw=${result.rawCandidateCount}, " +
                    "verified=${result.verifiedCandidateCount}, count=${result.methods.size}, status=${result.status}, " +
                    "elapsedMs=${System.currentTimeMillis() - startedAt}, " +
                    "items=${result.methods.take(8).joinToString { it.declaringClass.name + '#' + it.name }}",
            )
        }
        return result
    }

    private fun session(classLoader: ClassLoader): Session {
        val (current, previous) = synchronized(stateLock) {
            activeSession?.takeIf { it.classLoader === classLoader && !it.worker.isClosed }?.let { return it }
            val previous = activeSession
            val current = Session(generationCounter.incrementAndGet(), classLoader)
            activeSession = current
            current to previous
        }
        previous?.worker?.close()
        return current
    }

    private fun bridgeFor(context: Context, session: Session): DexKitBridge {
        session.bridge?.takeIf { it.isValid }?.let { return it }
        runCatching { session.bridge?.close() }
        session.bridge = null
        nativeUnavailableReason(context)?.let { reason ->
            throw NativeUnavailableException(reason)
        }
        return DexKitBridge.create(session.classLoader, true).also { created ->
            created.setThreadNum(1)
            session.bridge = created
        }
    }

    private fun ensureNativeLoaded(context: Context): Boolean {
        NativeAbiSupport.unsupportedReason()?.let { reason ->
            nativeFailureReason = reason
            return false
        }
        if (nativeLoadSucceeded) return true
        synchronized(stateLock) {
            if (nativeLoadSucceeded) return true
            if (!nativeLoadAttempted) {
                nativeLoadAttempted = true
                runCatching {
                    System.loadLibrary("dexkit")
                }.recoverCatching {
                    val nativeFile = NativeAbiSupport.nativeLibraryFile(context)
                        ?: error("${NativeAbiSupport.NATIVE_MISSING}:module-library")
                    System.load(nativeFile.absolutePath)
                }.onSuccess {
                    nativeLoadSucceeded = true
                    nativeFailureReason = null
                    ResolverEnvironment.info("DexKit native library loaded")
                }.onFailure { throwable ->
                    nativeFailureReason = NativeAbiSupport.NATIVE_LOAD_FAILED
                    ResolverEnvironment.warn(
                        "DexKit native load failed: error=${throwable.javaClass.name}, " +
                            "message=${throwable.message.orEmpty().take(160)}",
                    )
                }
            }
            return nativeLoadSucceeded
        }
    }

    private class Session(val generation: Long, val classLoader: ClassLoader) {
        // Accessed only by this session's worker and its termination callback.
        var bridge: DexKitBridge? = null
        val worker = ManagedExecutor(
            ownerKey = "host-resolver:$generation",
            threadName = "QDHelper-HostResolver",
            onFailure = { throwable ->
                ResolverEnvironment.warn("Host resolver worker failed: ${throwable.javaClass.name}")
            },
            onTerminated = {
                val closing = bridge
                bridge = null
                closing?.close()
            },
        )
    }

    private class NativeUnavailableException(val reason: String) : IllegalStateException(reason)
}

internal sealed interface DexKitScanResult {
    data class Candidates(val result: CandidatePipelineResult, val generation: Long) : DexKitScanResult
    data class Unavailable(val reason: String) : DexKitScanResult
}
