package com.xiyunmn.qdhelper.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeNetBridgeInstallGateTest {
    @Test
    fun installsOncePerClassLoaderRetriesFailuresAndResetsOnClear() {
        val gate = NativeNetBridgeInstallGate()
        val first = object : ClassLoader(null) {}
        val second = object : ClassLoader(null) {}
        var firstCalls = 0
        var secondCalls = 0

        assertEquals(
            NativeNetBridgeInstallResult.INSTALLED,
            gate.install(first) {
                firstCalls += 1
                true
            },
        )
        assertEquals(
            NativeNetBridgeInstallResult.ALREADY_INSTALLED,
            gate.install(first) {
                firstCalls += 1
                true
            },
        )
        assertEquals(1, firstCalls)

        assertEquals(
            NativeNetBridgeInstallResult.FAILED,
            gate.install(second) {
                secondCalls += 1
                false
            },
        )
        assertEquals(
            NativeNetBridgeInstallResult.INSTALLED,
            gate.install(second) {
                secondCalls += 1
                true
            },
        )
        assertEquals(2, secondCalls)

        gate.clear()
        assertEquals(
            NativeNetBridgeInstallResult.INSTALLED,
            gate.install(first) {
                firstCalls += 1
                true
            },
        )
        assertEquals(2, firstCalls)
    }
}
