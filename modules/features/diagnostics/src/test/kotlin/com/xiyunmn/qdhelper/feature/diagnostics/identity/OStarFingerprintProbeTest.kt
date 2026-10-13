package com.xiyunmn.qdhelper.feature.diagnostics.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OStarFingerprintProbeTest {
    /**
     * Mirrors the relevant shapes of `com.ola.star.aj.f` / `com.ola.star.uin.U`:
     * two public instance String builders (wanted), plus no-arg, static, and native
     * String methods (all unwanted).
     */
    @Suppress("unused")
    private open class FingerprintShapes {
        fun buildInner(a: Any, b: Any, c: Any): String = "$a$b$c"
        fun buildOuter(appKey: String): String = appKey
        fun noArguments(): String = ""
        fun wrongReturnType(value: String): Int = value.length
        external fun nativeFingerprint(context: Any): String

        companion object {
            @JvmStatic
            fun staticAccessor(value: String): String = value
        }
    }

    private fun matched(): List<String> = FingerprintShapes::class.java.declaredMethods
        .filter(OStarFingerprintProbe::isSafeFingerprintSource)
        .map { it.name }
        .sorted()

    @Test
    fun onlyPublicInstanceStringBuildersWithArgumentsMatch() {
        assertEquals(listOf("buildInner", "buildOuter"), matched())
    }

    @Test
    fun nativeAccessorsAreNeverHooked() {
        // Hooking JNI under the host's hardening is unreliable and out of scope for a
        // read-only probe; this is the invariant that keeps the probe off `uin.U`.
        assertFalse(matched().contains("nativeFingerprint"))
    }

    @Test
    fun noArgumentStaticAndNonStringMethodsAreExcluded() {
        val names = matched()
        assertFalse(names.contains("noArguments"))
        assertFalse(names.contains("staticAccessor"))
        assertFalse(names.contains("wrongReturnType"))
    }

    @Test
    fun syntheticAndBridgeMethodsAreExcluded() {
        val synthetic = FingerprintShapes::class.java.declaredMethods.filter { it.isSynthetic || it.isBridge }
        assertTrue(synthetic.none(OStarFingerprintProbe::isSafeFingerprintSource))
    }
}
