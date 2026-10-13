package com.xiyunmn.qdhelper.feature.diagnostics.target

import org.junit.Assert.*
import org.junit.Test

class HookTargetSpecTest {
    @Test fun structuralProbeAcceptsRenamesButRejectsWrongOrderAndInstanceMethods() {
        val spec = HookTargetSpec("sample", Fixture::class.java.name, "oldName", "fixture",
            returnTypeName = "boolean", publicStaticParameters = listOf("java.lang.String", "int"))
        Fixture::class.java.declaredMethods.forEach { method ->
            assertEquals(method.name, method.name in setOf("oldName", "renamed"), spec.matches(method))
        }
    }

    @Test fun namedProbesStillRequireTheirDeclaredNamesAndShape() {
        val spec = HookTargetSpec("sample", Fixture::class.java.name, "oldName", "fixture",
            parameterCount = 2, returnTypeName = "boolean")
        Fixture::class.java.declaredMethods.forEach { method ->
            assertEquals(method.name, method.name == "oldName", spec.matches(method))
        }
    }

    private class Fixture {
        fun instance(value: String, count: Int) = false
        companion object {
            @JvmStatic fun oldName(value: String, count: Int) = true
            @JvmStatic fun renamed(value: String, count: Int) = true
            @JvmStatic fun wrongOrder(count: Int, value: String) = true
            @JvmStatic fun wrongType(value: String, count: Long) = true
        }
    }
}
