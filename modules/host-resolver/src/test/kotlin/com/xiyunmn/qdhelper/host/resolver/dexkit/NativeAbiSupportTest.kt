package com.xiyunmn.qdhelper.host.resolver.dexkit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeAbiSupportTest {
    @Test
    fun arm64DeviceAnd64BitProcessAreSupported() {
        assertNull(NativeAbiSupport.unsupportedReason(listOf("arm64-v8a", "armeabi-v7a"), true))
    }

    @Test
    fun missingArm64DeviceAbiIsRejectedBeforeProcessCheck() {
        assertEquals(
            NativeAbiSupport.UNSUPPORTED_ABI,
            NativeAbiSupport.unsupportedReason(listOf("armeabi-v7a", "x86"), false),
        )
    }

    @Test
    fun bit32HostProcessIsRejectedOnArm64Device() {
        assertEquals(
            NativeAbiSupport.UNSUPPORTED_PROCESS_ABI,
            NativeAbiSupport.unsupportedReason(listOf("arm64-v8a"), false),
        )
    }
}
