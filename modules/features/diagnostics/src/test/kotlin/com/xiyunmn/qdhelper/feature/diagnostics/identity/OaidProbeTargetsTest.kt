package com.xiyunmn.qdhelper.feature.diagnostics.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OaidProbeTargetsTest {
    @Test
    fun excludesAbstractInterfaceMethodsAndSelectsConcreteStringGetters() {
        assertTrue(
            OaidProbeTargets.concreteStringGetters(AbstractSupplier::class.java.declaredMethods.asList()).isEmpty(),
        )
        assertEquals(
            listOf("getAAID", "getOAID", "getVAID"),
            OaidProbeTargets.concreteStringGetters(ConcreteSupplier::class.java.declaredMethods.asList()).map { it.name },
        )
    }

    private interface AbstractSupplier {
        fun getOAID(): String
        fun getVAID(): String
        fun getAAID(): String
    }

    private class ConcreteSupplier {
        fun getOAID(): String = "oaid"
        fun getVAID(): String = "vaid"
        fun getAAID(): String = "aaid"
        fun getOAID(scope: String): String = scope
        fun getUnsupported(): Int = 0
    }
}
