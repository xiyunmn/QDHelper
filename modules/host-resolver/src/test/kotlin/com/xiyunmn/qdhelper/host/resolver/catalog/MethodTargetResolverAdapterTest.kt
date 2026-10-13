package com.xiyunmn.qdhelper.host.resolver.catalog

import android.content.Context
import android.content.ContextWrapper
import com.qidian.QDReader.ui.activity.SplashActivity
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import com.xiyunmn.qdhelper.host.api.target.Cardinality
import com.xiyunmn.qdhelper.host.api.target.DialogTargetKeys
import com.xiyunmn.qdhelper.host.api.target.HostContractResolver
import com.xiyunmn.qdhelper.host.api.target.ReaderTargetKeys
import com.xiyunmn.qdhelper.host.api.target.Resolution
import com.xiyunmn.qdhelper.host.api.target.StartupTargetKeys
import com.xiyunmn.qdhelper.host.api.target.TargetKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Method

class MethodTargetResolverAdapterTest {
    private val classLoader = requireNotNull(javaClass.classLoader)
    private val defaultSplash = SplashActivity::class.java.getDeclaredMethod("initDefaultSplash")
    private val otherDefaultSplash = SplashActivity::class.java.getDeclaredMethod("otherDefaultSplash")

    @Test
    fun namedSingleTargetUsesCanonicalResolver() {
        val expected = Fixture::class.java.getDeclaredMethod("first")
        val resolver = FakeResolver(single = expected)

        val actual = adapter(resolver).resolveMethod(
            FakeContext(),
            classLoader,
            HostSymbols.PACKAGE,
            ReaderTargetKeys.chapterEndAppend,
            forceRescan = false,
        )

        assertSame(expected, actual)
        assertEquals(listOf(ReaderTargetKeys.chapterEndAppend.id.value), resolver.requestedIds)
    }

    @Test
    fun namedManyTargetPreservesResolverOrder() {
        val expected = listOf(
            Fixture::class.java.getDeclaredMethod("first"),
            Fixture::class.java.getDeclaredMethod("second"),
        )
        val resolver = FakeResolver(many = expected)

        val actual = adapter(resolver).resolveMethods(
            FakeContext(),
            classLoader,
            HostSymbols.PACKAGE,
            com.xiyunmn.qdhelper.host.api.target.CircleTargetKeys.detailBindMethods,
            forceRescan = true,
        )

        assertEquals(expected, actual)
        assertEquals(listOf(true), resolver.forceRescanRequests)
    }

    @Test
    fun listConsumerCanUseSingleCardinalityTarget() {
        val expected = Fixture::class.java.getDeclaredMethod("first")

        val actual = adapter(FakeResolver(single = expected)).resolveMethods(
            FakeContext(),
            classLoader,
            HostSymbols.PACKAGE,
            ReaderTargetKeys.chapterEndAppend,
            forceRescan = false,
        )

        assertEquals(listOf(expected), actual)
        assertTrue(ReaderTargetKeys.chapterEndAppend.cardinality is Cardinality.One)
    }

    @Test
    fun earlyTargetUsesCanonicalResolverBeforeRegistry() {
        var registryCalled = false
        val actual = adapter(FakeResolver(single = defaultSplash)) { _, _ ->
            registryCalled = true
            listOf(otherDefaultSplash)
        }.resolveEarlyMethod(
            FakeContext(),
            classLoader,
            HostSymbols.PACKAGE,
            StartupTargetKeys.splashActivityInitDefault,
        )

        assertSame(defaultSplash, actual)
        assertFalse(registryCalled)
    }

    @Test
    fun missingContextUsesVerifiedEarlyRegistryCandidate() {
        val resolver = FakeResolver(single = otherDefaultSplash)
        val actual = adapter(resolver) { _, _ -> listOf(defaultSplash) }.resolveEarlyMethod(
            null,
            classLoader,
            HostSymbols.PACKAGE,
            StartupTargetKeys.splashActivityInitDefault,
        )

        assertSame(defaultSplash, actual)
        assertTrue(resolver.requestedIds.isEmpty())
    }

    @Test
    fun unavailableResolverUsesVerifiedEarlyRegistryCandidate() {
        val actual = adapter(FakeResolver(unavailableReason = "deferred_to_next_start:fixture")) { _, _ ->
            listOf(defaultSplash)
        }.resolveEarlyMethod(
            FakeContext(),
            classLoader,
            HostSymbols.PACKAGE,
            StartupTargetKeys.splashActivityInitDefault,
        )

        assertSame(defaultSplash, actual)
    }

    @Test
    fun earlyRegistryCandidateMustPassCanonicalVerifier() {
        val wrongOwner = Fixture::class.java.getDeclaredMethod("first")
        val actual = adapter(FakeResolver(unavailableReason = "resolver_unavailable:fixture")) { _, _ ->
            listOf(wrongOwner)
        }.resolveEarlyMethod(
            FakeContext(),
            classLoader,
            HostSymbols.PACKAGE,
            StartupTargetKeys.splashActivityInitDefault,
        )

        assertNull(actual)
    }

    @Test
    fun earlyRegistryFailureFailsClosed() {
        val actual = adapter(FakeResolver(unavailableReason = "resolver_unavailable:fixture")) { _, _ ->
            error("fixture-registry-failure")
        }.resolveEarlyMethod(
            FakeContext(),
            classLoader,
            HostSymbols.PACKAGE,
            StartupTargetKeys.splashActivityInitDefault,
        )

        assertNull(actual)
    }

    @Test
    fun nonEarlyTargetCannotUseRegistry() {
        var registryCalled = false
        val actual = adapter(FakeResolver(unavailableReason = "resolver_unavailable:fixture")) { _, _ ->
            registryCalled = true
            listOf(defaultSplash)
        }.resolveEarlyMethod(
            FakeContext(),
            classLoader,
            HostSymbols.PACKAGE,
            ReaderTargetKeys.chapterEndAppend,
        )

        assertNull(actual)
        assertFalse(registryCalled)
    }

    @Test
    fun ambiguousOrMultipleEarlyCandidatesFailClosed() {
        var registryCalled = false
        val ambiguous = adapter(FakeResolver(ambiguous = true)) { _, _ ->
            registryCalled = true
            listOf(defaultSplash)
        }.resolveEarlyMethod(
            FakeContext(),
            classLoader,
            HostSymbols.PACKAGE,
            StartupTargetKeys.splashActivityInitDefault,
        )
        val multiple = adapter(FakeResolver(unavailableReason = "resolver_unavailable:fixture")) { _, _ ->
            listOf(defaultSplash, otherDefaultSplash)
        }.resolveEarlyMethod(
            FakeContext(),
            classLoader,
            HostSymbols.PACKAGE,
            StartupTargetKeys.splashActivityInitDefault,
        )

        assertNull(ambiguous)
        assertFalse(registryCalled)
        assertNull(multiple)
    }

    @Test
    fun nativeAbiFailuresDisableEarlyRegistry() {
        listOf(
            "unsupported_abi",
            "unsupported_process_abi",
            "dexkit_native_missing:module-library",
            "native_load_failed:UnsatisfiedLinkError",
            "dexkit_unavailable:unsupported_abi",
            "dexkit_unavailable:unsupported_process_abi",
            "dexkit_unavailable:dexkit_native_missing:module-library",
            "dexkit_unavailable:native_load_failed:UnsatisfiedLinkError",
        ).forEach { reason ->
            var registryCalled = false
            val actual = adapter(FakeResolver(unavailableReason = reason)) { _, _ ->
                registryCalled = true
                listOf(defaultSplash)
            }.resolveEarlyMethod(
                FakeContext(),
                classLoader,
                HostSymbols.PACKAGE,
                StartupTargetKeys.splashActivityInitDefault,
            )

            assertNull("reason=$reason", actual)
            assertFalse("reason=$reason", registryCalled)
        }
    }

    @Test
    fun registryAllowListContainsOnlyAuditedEarlyTargets() {
        assertEquals(
            StartupTargetKeys.all.map { it.id }.toSet() + DialogTargetKeys.autoUpdateEntry.id,
            EarlyMethodCandidateRegistry.targetIds,
        )
    }

    private fun adapter(
        resolver: HostContractResolver,
        earlyCandidates: (ClassLoader, TargetKey.MethodTarget) -> List<Method> = { _, _ -> emptyList() },
    ) = MethodTargetResolverAdapter(
        resolverFactory = { _, _, _ -> resolver },
        earlyCandidateProvider = earlyCandidates,
    )

    private class FakeContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }

    private class FakeResolver(
        private val single: Method? = null,
        private val many: List<Method> = emptyList(),
        private val ambiguous: Boolean = false,
        private val unavailableReason: String? = null,
    ) : HostContractResolver {
        val requestedIds = mutableListOf<String>()
        val forceRescanRequests = mutableListOf<Boolean>()

        override fun <T : Any> resolve(key: TargetKey<T>, forceRescan: Boolean): Resolution<T> {
            requestedIds += key.id.value
            forceRescanRequests += forceRescan
            val result: Resolution<*> = when {
                ambiguous -> Resolution.Ambiguous(2, "fixture")
                unavailableReason != null -> Resolution.Unavailable(unavailableReason, true)
                key.cardinality is Cardinality.Many -> Resolution.ResolvedMany(many, Resolution.Source.CACHE)
                single != null -> Resolution.Resolved(single, Resolution.Source.CACHE)
                else -> Resolution.Unavailable("fixture_missing", true)
            }
            @Suppress("UNCHECKED_CAST")
            return result as Resolution<T>
        }
    }

    @Suppress("unused")
    private class Fixture {
        fun first() = Unit
        fun second() = Unit
    }
}
