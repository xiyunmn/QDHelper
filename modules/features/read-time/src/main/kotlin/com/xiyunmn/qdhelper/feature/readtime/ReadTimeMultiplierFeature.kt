package com.xiyunmn.qdhelper.feature.readtime

import android.os.SystemClock
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import com.xiyunmn.qdhelper.feature.api.runtime.InstallableFeature
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import com.xiyunmn.qdhelper.host.api.target.HostTargetResolver
import com.xiyunmn.qdhelper.host.api.target.ReadTimeTargetKeys
import com.xiyunmn.qdhelper.host.api.target.TargetKey
import java.lang.reflect.Method
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Audits every reading-time persistence attempt, optionally scales it, and observes the host's
 * network-success batch marker. It also keeps exact original durations for local display.
 *
 * Injection happens at the single persistence funnel `boolean(ReadTimeEntity)`, where the host
 * derives the encrypted `EncodeReadTime` copy from the plaintext `ReadTime`. Adjusting the
 * plaintext on entry therefore keeps both representations consistent without the module ever
 * touching the DES key — and it covers reading, listening and dubbing alike, since all of them
 * converge here.
 */
object ReadTimeMultiplierFeature : InstallableFeature {
    override val name: String = "ReadTimeMultiplierFeature"

    private const val FIRST_VERBOSE = 10L
    private const val SAMPLE_EVERY = 25L

    private val scaleCount = AtomicLong()

    override fun install(
        module: HookModule,
        classLoader: ClassLoader,
        processName: String,
        phase: String,
    ) {
        if (!HostSymbols.isMainProcess(processName)) return
        HostSessionStore.contextOrNull()?.let { ReadTimeRuntime.session(it, classLoader) }

        var installed = 0
        installed += installSessionScaling(module, classLoader, phase)
        installed += installReportedBatchAudit(module, classLoader, phase)
        installed += installLocalDisplayStatistics(module, classLoader, phase)
        installed += installLocalDisplayBinding(module, classLoader, phase)
        FeatureHooks.i(
            "Read time audit phase finished: phase=$phase, process=$processName, installedHooks=$installed",
        )
    }

    private fun installSessionScaling(module: HookModule, classLoader: ClassLoader, phase: String): Int {
        val insert = resolveOne(classLoader, ReadTimeTargetKeys.statisticsInsert) ?: run {
            FeatureHooks.w("Read time multiplier: statistics insert unresolved, phase=$phase")
            return 0
        }
        val entityClass = FeatureHooks.findClassOrNull(HostSymbols.Classes.READ_TIME_ENTITY, classLoader)
        val readTimeField = entityClass?.let { entity -> FeatureHooks.findFieldOrNull(entity, "ReadTime") }
            ?: run {
                FeatureHooks.w("Read time multiplier: ReadTime field unavailable, phase=$phase")
                return 0
            }
        val entityReader = ReadTimeHostEntityReader(entityClass)

        val ok = FeatureHooks.hookAround(
            module = module,
            executable = insert,
            feature = "read_time.session_scale",
            id = "read_time.session_scale:${insert.toGenericString()}",
        ) { chain ->
            val entity = chain.args.firstOrNull() ?: return@hookAround chain.proceed()
            val original = runCatching { (FeatureHooks.read(readTimeField, entity) as? Number)?.toLong() }
                .onFailure { throwable ->
                    FeatureHooks.w("Read time audit capture skipped: ${throwable.javaClass.simpleName}")
                }
                .getOrNull()
                ?: return@hookAround chain.proceed()
            val details = entityReader.details(entity)
            val originalEncoding = entityReader.captureEncoding(entity)
            val multiplier = activeMultiplier(details.userId)
            val requested = multiplier?.let { ReadTimeMultiplierPolicy.scaleSession(original, it) } ?: original
            val adjustmentRequested = multiplier != null && requested != original
            val detail = multiplier?.let { ReadTimeMultiplierPolicy.describeScaling(original, requested, it) }
            val clamped = multiplier?.let { ReadTimeMultiplierPolicy.isClamped(original, requested, it) } == true
            var adjustmentApplied = false
            var scaleError: String? = null
            var restoreFailed = false
            var encodedAfterDao = false
            val startedAt = SystemClock.elapsedRealtime()

            try {
                val result = if (multiplier != null) {
                    ReadTimePersistenceScaling.proceed(
                        original = original,
                        scaled = requested,
                        write = { value -> FeatureHooks.write(readTimeField, entity, value) },
                        persist = chain::proceed,
                        onPersisted = { detail?.let(::logScaled) },
                        onScaleApplied = { adjustmentApplied = true },
                        onScaleFailed = { throwable ->
                            scaleError = throwable?.javaClass?.simpleName ?: "write returned false"
                            FeatureHooks.w("Read time scaling skipped: $scaleError")
                        },
                        restoreRelatedState = {
                            encodedAfterDao = entityReader.encodingPresent(entity)
                            entityReader.restoreEncoding(entity, originalEncoding)
                        },
                        onRestoreFailed = {
                            restoreFailed = true
                            FeatureHooks.w("Read time entity restore failed")
                        },
                    )
                } else {
                    chain.proceed()
                }
                if (!adjustmentApplied) encodedAfterDao = entityReader.encodingPresent(entity)
                recordAudit(
                    ReadTimeAuditEvent(
                        recordedAt = System.currentTimeMillis(),
                        originalMillis = original,
                        requestedMillis = requested,
                        persistedMillis = if (adjustmentApplied) requested else original,
                        multiplier = multiplier,
                        adjustmentRequested = adjustmentRequested,
                        adjustmentApplied = adjustmentApplied,
                        clamped = adjustmentApplied && clamped,
                        daoSuccess = result == true,
                        daoError = null,
                        scaleError = scaleError,
                        restoreFailed = restoreFailed,
                        encodedAfterDao = encodedAfterDao,
                        daoLatencyMillis = SystemClock.elapsedRealtime() - startedAt,
                        details = details,
                    ),
                    classLoader,
                )
                result
            } catch (throwable: Throwable) {
                if (!adjustmentApplied) encodedAfterDao = entityReader.encodingPresent(entity)
                recordAudit(
                    ReadTimeAuditEvent(
                        recordedAt = System.currentTimeMillis(),
                        originalMillis = original,
                        requestedMillis = requested,
                        persistedMillis = if (adjustmentApplied) requested else original,
                        multiplier = multiplier,
                        adjustmentRequested = adjustmentRequested,
                        adjustmentApplied = adjustmentApplied,
                        clamped = adjustmentApplied && clamped,
                        daoSuccess = false,
                        daoError = throwable.javaClass.simpleName,
                        scaleError = scaleError,
                        restoreFailed = restoreFailed,
                        encodedAfterDao = encodedAfterDao,
                        daoLatencyMillis = SystemClock.elapsedRealtime() - startedAt,
                        details = details,
                    ),
                    classLoader,
                )
                throw throwable
            }
        }
        return if (ok) 1 else 0
    }

    private fun installReportedBatchAudit(module: HookModule, classLoader: ClassLoader, phase: String): Int {
        val markReported = resolveOne(classLoader, ReadTimeTargetKeys.statisticsMarkReported) ?: run {
            FeatureHooks.w("Read time audit: reported batch marker unresolved, phase=$phase")
            return 0
        }
        val reportEntity = FeatureHooks.findClassOrNull(HostSymbols.Classes.READ_TIME_REPORT_ENTITY, classLoader)
            ?: run {
                FeatureHooks.w("Read time audit: ReportEntity unavailable, phase=$phase")
                return 0
            }
        val reader = ReadTimeReportedBatchReader(reportEntity)
        val ok = FeatureHooks.hookAround(
            module = module,
            executable = markReported,
            feature = "read_time.report_audit",
            id = "read_time.report_audit:${markReported.toGenericString()}",
        ) { chain ->
            val keys = reader.keys(chain.args.firstOrNull())
            val result = chain.proceed()
            val context = HostSessionStore.contextOrNull()
            if (context != null && keys.isNotEmpty()) {
                val reportedAccounts = runCatching {
                    ReadTimeReportAuditStore.markReported(context, keys, System.currentTimeMillis())
                }.onFailure { throwable ->
                    FeatureHooks.w("Read time report audit update failed: ${throwable.javaClass.simpleName}")
                }.getOrDefault(emptySet())
                FeatureHooks.i("Read time host network-success batch observed: records=${keys.size}")
                ReadTimeRuntime.session(context, classLoader)?.scheduleAutomatic(reportedAccounts)
            }
            result
        }
        return if (ok) 1 else 0
    }

    /**
     * Replaces the host's scaled cloud totals with exact original durations recorded locally.
     *
     * The page renders values returned by the cloud endpoint, not the local SDK table, so the
     * override lives on the response model getters. The hook reads the setting on every call so a
     * live toggle does not leave behavior pinned to the value seen during feature installation.
     *
     * No multiplier inversion is involved, so changing the multiplier or hitting the per-session
     * cap cannot distort the local total. Sessions from before this database existed are not
     * fabricated; the current day's retained audit events are imported once as an exact bootstrap.
     */
    private fun installLocalDisplayStatistics(module: HookModule, classLoader: ClassLoader, phase: String): Int {
        val readInfo = FeatureHooks.findClassOrNull(HostSymbols.Classes.READ_TIME_READ_INFO, classLoader)
            ?: run {
                FeatureHooks.w("Read time local display: ReadInfoBean missing, phase=$phase")
                return 0
            }
        var installed = 0
        listOf("getTodayReadTime", "getWeekReadTime").forEach { getterName ->
            val getter = FeatureHooks.declaredMethodsNamed(readInfo, getterName).firstOrNull { method ->
                method.parameterTypes.isEmpty() && method.returnType == Int::class.javaPrimitiveType
            } ?: run {
                FeatureHooks.w("Read time local display: $getterName unavailable, phase=$phase")
                return@forEach
            }
            val ok = FeatureHooks.hookAround(
                module = module,
                executable = getter,
                feature = "read_time.local_display",
                id = "read_time.local_display:${getter.toGenericString()}",
            ) { chain ->
                val reported = chain.proceed()
                val context = HostSessionStore.contextOrNull()
                if (reported !is Int || context == null || !SettingsRuntime.isReadTimeLocalDisplayRealEnabled(context)) {
                    return@hookAround reported
                }
                val userId = ReadTimeRuntime.userId(context) ?: return@hookAround reported
                val session = ReadTimeRuntime.session(context, classLoader) ?: return@hookAround reported
                runCatching { session.statistics.totals(userId) }
                    .map { totals ->
                        if (getterName == "getTodayReadTime") totals.todaySeconds else totals.weekSeconds
                    }
                    .onFailure { throwable ->
                        FeatureHooks.w("Read time local database query failed: ${throwable.javaClass.simpleName}")
                    }
                    .getOrDefault(reported)
            }
            if (ok) installed++
        }
        return installed
    }

    /**
     * Hooks the page binding method as a stable fallback for runtimes that inline the tiny model
     * getters. Values are replaced only while the title views are rendered and are restored before
     * returning, so raw cloud diagnostics keep seeing the untouched response fields.
     */
    private fun installLocalDisplayBinding(module: HookModule, classLoader: ClassLoader, phase: String): Int {
        val activity = FeatureHooks.findClassOrNull(HostSymbols.Classes.READ_TIME_MAIN_PAGE_ACTIVITY, classLoader)
            ?: run {
                FeatureHooks.w("Read time local display: main page activity missing, phase=$phase")
                return 0
            }
        val readInfo = FeatureHooks.findClassOrNull(HostSymbols.Classes.READ_TIME_READ_INFO, classLoader)
            ?: return 0
        val bind = FeatureHooks.declaredMethodsNamed(activity, "bindTitleData").firstOrNull { method ->
            method.parameterTypes.contentEquals(arrayOf(readInfo)) && method.returnType == Void.TYPE
        } ?: run {
            FeatureHooks.w("Read time local display: bindTitleData unavailable, phase=$phase")
            return 0
        }
        val todayField = FeatureHooks.findFieldOrNull(readInfo, "TodayReadTime") ?: return 0
        val weekField = FeatureHooks.findFieldOrNull(readInfo, "WeekReadTime") ?: return 0
        val ok = FeatureHooks.hookAround(
            module = module,
            executable = bind,
            feature = "read_time.local_display.binding",
            id = "read_time.local_display.binding:${bind.toGenericString()}",
        ) { chain ->
            val model = chain.args.firstOrNull()
            val context = HostSessionStore.contextOrNull()
            if (model == null || context == null || !SettingsRuntime.isReadTimeLocalDisplayRealEnabled(context)) {
                return@hookAround chain.proceed()
            }
            val originalToday = FeatureHooks.read(todayField, model)
            val originalWeek = FeatureHooks.read(weekField, model)
            val userId = ReadTimeRuntime.userId(context) ?: return@hookAround chain.proceed()
            val session = ReadTimeRuntime.session(context, classLoader) ?: return@hookAround chain.proceed()
            val totals = runCatching { session.statistics.totals(userId) }
                .onFailure { throwable ->
                    FeatureHooks.w("Read time local binding query failed: ${throwable.javaClass.simpleName}")
                }
                .getOrNull() ?: return@hookAround chain.proceed()
            if (!FeatureHooks.write(todayField, model, totals.todaySeconds) ||
                !FeatureHooks.write(weekField, model, totals.weekSeconds)
            ) {
                FeatureHooks.write(todayField, model, originalToday)
                FeatureHooks.write(weekField, model, originalWeek)
                return@hookAround chain.proceed()
            }
            try {
                chain.proceed()
            } finally {
                FeatureHooks.write(todayField, model, originalToday)
                FeatureHooks.write(weekField, model, originalWeek)
            }
        }
        return if (ok) 1 else 0
    }

    private fun activeMultiplier(userId: Long?): Double? {
        val context = HostSessionStore.contextOrNull()
        if (!SettingsRuntime.isReadTimeMultiplierEnabled(context)) return null
        val raw = SettingsRuntime.readTimeMultiplierRaw(context)
        val configured = ReadTimeMultiplierPolicy.parseMultiplier(raw) ?: if (raw.isBlank()) {
            ReadTimeMultiplierPolicy.DEFAULT_MULTIPLIER
        } else {
            ReadTimeMultiplierPolicy.migrateStoredMultiplier(raw)
        } ?: return null
        if (context != null && ReadTimeMultiplierPolicy.parseMultiplier(raw) == null) {
            SettingsRuntime.putReadTimeMultiplier(context, String.format(Locale.US, "%.1f", configured))
        }
        val automaticLimit = context?.let { ReadTimeCloudAuditStore.automaticLimit(it, userId) }
        return ReadTimeMultiplierPolicy.effectiveMultiplier(configured, automaticLimit)
    }

    private fun resolveOne(
        classLoader: ClassLoader,
        key: TargetKey.MethodTarget,
    ): Method? = HostTargetResolver.resolveMethod(
        context = HostSessionStore.contextOrNull(),
        classLoader = classLoader,
        processName = HostSessionStore.processName,
        key = key,
    )

    private fun logScaled(detail: String) {
        val count = scaleCount.incrementAndGet()
        if (count <= FIRST_VERBOSE || count % SAMPLE_EVERY == 0L) {
            FeatureHooks.i("Read time scaled#$count: $detail")
        }
    }

    private fun recordAudit(event: ReadTimeAuditEvent, classLoader: ClassLoader) {
        val context = HostSessionStore.contextOrNull() ?: return
        val session = ReadTimeRuntime.session(context, classLoader) ?: return
        session.record(event)
    }
}
