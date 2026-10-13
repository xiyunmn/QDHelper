package com.xiyunmn.qdhelper.host.resolver.dexkit.query

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures mirror `AppConfig${'$'}init${'$'}1${'$'}1 extends judian<AppConfigBean>`: the stage
 * callbacks are generic overrides on an anonymous subclass, so the compiler emits a bridge
 * method taking `Object` alongside each concrete override.
 */
class AppConfigStageContractTest {
    private class AppConfigBeanFixture

    private abstract class ConfigLoaderFixture<T> {
        abstract fun f(config: T)
        abstract fun g(config: T)
    }

    private class StageFixture : ConfigLoaderFixture<AppConfigBeanFixture>() {
        override fun f(config: AppConfigBeanFixture) = Unit
        override fun g(config: AppConfigBeanFixture) = Unit
        fun unrelated(config: AppConfigBeanFixture): String = config.toString()
    }

    /** Declares the right shape but sits outside the loader hierarchy. */
    private class ForeignFixture {
        fun f(config: AppConfigBeanFixture) = Unit
    }

    private fun isStage(method: java.lang.reflect.Method): Boolean = HostMethodContracts.isAppConfigStage(
        method,
        configLoaderName = ConfigLoaderFixture::class.java.name,
        appConfigBeanName = AppConfigBeanFixture::class.java.name,
    )

    @Test
    fun bothStageOverridesMatchThroughTheInheritanceAnchor() {
        val matched = StageFixture::class.java.declaredMethods
            .filter(::isStage)
            .map { it.name }
            .sorted()

        // The synthetic anonymous class name is never consulted; only the supertype is.
        assertTrue(matched.containsAll(listOf("f", "g")))
    }

    @Test
    fun genericBridgeMethodsAreExcluded() {
        val bridges = StageFixture::class.java.declaredMethods.filter { it.isBridge || it.isSynthetic }

        // Bridges take Object, so the concrete-parameter requirement already rejects them;
        // this pins the behaviour so a future relaxation cannot double-install the hook.
        assertTrue(bridges.isNotEmpty())
        assertTrue(bridges.none(::isStage))
    }

    @Test
    fun methodsOutsideTheLoaderHierarchyAreRejected() {
        assertFalse(isStage(ForeignFixture::class.java.getDeclaredMethod("f", AppConfigBeanFixture::class.java)))
    }

    @Test
    fun nonVoidMethodsOnTheLoaderAreRejected() {
        assertFalse(isStage(StageFixture::class.java.getDeclaredMethod("unrelated", AppConfigBeanFixture::class.java)))
    }
}
