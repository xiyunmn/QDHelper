package com.xiyunmn.qdhelper.host.resolver.dexkit.query

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.luckypray.dexkit.query.FindMethod

class DexKitQueryDslTest {
    @Test
    fun anchoredStringGetterVerifierDistinguishesStaticAndSingletonInstanceMethods() {
        val staticMethod = StaticGetterFixture::class.java.getDeclaredMethod("value")
        val instanceMethod = InstanceGetterFixture::class.java.getDeclaredMethod("value")

        val staticQuery = anchoredStringGetterQuery(
            id = "test.static",
            purpose = "test",
            searchPackage = StaticGetterFixture::class.java.packageName,
            anchor = "anchor",
            ownerName = StaticGetterFixture::class.java.name,
        )
        val instanceQuery = anchoredStringGetterQuery(
            id = "test.instance",
            purpose = "test",
            searchPackage = InstanceGetterFixture::class.java.packageName,
            anchor = "anchor",
            ownerName = InstanceGetterFixture::class.java.name,
            requireStatic = false,
        )

        assertTrue(staticQuery.verify(staticMethod))
        assertFalse(staticQuery.verify(instanceMethod))
        assertTrue(instanceQuery.verify(instanceMethod))
        assertFalse(instanceQuery.verify(staticMethod))
    }

    @Test
    fun autoUpdateShapeAcceptsBooleanCallbackEntryOutsideSecondParameter() {
        assertTrue(hasAutoUpdateJudianShape(AutoUpdateShapeFixture::class.java))
        assertFalse(hasAutoUpdateJudianShape(InvalidAutoUpdateShapeFixture::class.java))
    }

    @Test
    fun namedMethodVerifierCanRequireExactDeclaringClass() {
        val query = namedMethod(
            id = "test.named.owner",
            purpose = "test",
            searchPackage = NamedOwnerFixture::class.java.name.substringBeforeLast('.'),
            methodName = "doCall",
            paramCount = 0,
            returnType = Void.TYPE,
            ownerName = NamedOwnerFixture::class.java.name,
        )

        assertTrue(query.verify(NamedOwnerFixture::class.java.getDeclaredMethod("doCall")))
        assertFalse(query.verify(OtherNamedOwnerFixture::class.java.getDeclaredMethod("doCall")))
    }

    @Test
    fun namedMethodCanVerifyShapeWithoutLockingObfuscatedName() {
        val query = namedMethod(
            id = "test.named.shape",
            purpose = "test",
            searchPackage = ShapeFixture::class.java.packageName,
            methodName = "legacyName",
            paramCount = 2,
            paramTypes = listOf(String::class.java.name, null),
            returnTypeName = Long::class.javaPrimitiveType!!.name,
            ownerName = ShapeFixture::class.java.name,
            matchName = false,
        )

        assertTrue(query.verify(ShapeFixture::class.java.getDeclaredMethod("renamed", String::class.java, Int::class.javaPrimitiveType!!)))
        assertFalse(query.verify(ShapeFixture::class.java.getDeclaredMethod("wrongReturn", String::class.java, Int::class.javaPrimitiveType!!)))
    }

    @Test
    fun migratedObfuscatedTargetsDoNotInstallMethodNameMatchers() {
        val queries = listOf(
            AccountNetworkWelfareQueries.autoSignInManagerInstance,
            AccountNetworkWelfareQueries.autoSignInCheckInRequest,
            AccountNetworkWelfareQueries.autoSignInAlreadyCheckedIn,
            AccountNetworkWelfareQueries.autoSignInSyncCheckedIn,
            AccountNetworkWelfareQueries.qdUserManagerIsLoggedIn,
            AccountNetworkWelfareQueries.qdUserManagerUserId,
            ReaderStartupQueries.readPageChapterEndAppend,
            UiTargetQueries.themeNightMode,
            UiTargetQueries.bottomTabSetRedPoint,
            UiTargetQueries.bottomTabSetTagText,
            UiTargetQueries.bottomTabSetOperationRedDot,
            UiTargetQueries.bookshelfOperationParseLambda,
        )

        assertTrue(queries.all { query ->
            requireNotNull(FindMethod.create().apply(query.query).matcher).nameMatcher == null
        })
    }

    @Test
    fun bookshelfNotifyQueriesUseStableKotlinLambdaSuffixes() {
        val matchers = listOf(
            UiTargetQueries.bookshelfOperationNotifyLambda13,
            UiTargetQueries.bookshelfOperationNotifyLambda0,
        ).map { query -> requireNotNull(FindMethod.create().apply(query.query).matcher) }

        assertTrue(matchers.all { it.nameMatcher != null })
        assertTrue(matchers.all { requireNotNull(it.usingStringsMatcher).size == 1 })
    }

    private class StaticGetterFixture {
        companion object {
            @JvmStatic
            fun value(): String = "anchor"
        }
    }

    private class InstanceGetterFixture {
        fun value(): String = "anchor"
    }

    private class NamedOwnerFixture {
        fun doCall() = Unit
    }

    private class OtherNamedOwnerFixture {
        fun doCall() = Unit
    }

    private class ShapeFixture {
        fun renamed(value: String, count: Int): Long = value.length.toLong() + count
        fun wrongReturn(value: String, count: Int): Int = value.length + count
    }

    private class AutoUpdateShapeFixture {
        interface Callback {
            fun a()
            fun b()
            fun c()
            fun d()
            fun e()
            fun f()
            fun g()
            fun h()
        }

        companion object {
            @JvmStatic
            fun shifted(context: String, enabled: Boolean, handler: Long, callback: Callback): Boolean = false
        }
    }

    private class InvalidAutoUpdateShapeFixture {
        interface Callback {
            fun a()
            fun b()
            fun c()
            fun d()
            fun e()
            fun f()
            fun g()
            fun h()
        }

        fun shifted(context: String, enabled: Boolean, handler: Long, callback: Callback): Boolean = false
    }
}
