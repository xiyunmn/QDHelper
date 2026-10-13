package com.xiyunmn.qdhelper.foundation.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidApiSupportTest {
    @Test
    fun apiBelow28IsUnsupported() {
        assertEquals(AndroidApiSupport.Status.UNSUPPORTED, AndroidApiSupport.status(27))
        assertEquals(AndroidApiSupport.UNSUPPORTED_API, AndroidApiSupport.unsupportedReason(27))
        assertFalse(AndroidApiSupport.isSupported(27))
    }

    @Test
    fun api28Through37AreSupported() {
        (28..37).forEach { sdk ->
            assertEquals(AndroidApiSupport.Status.VERIFIED, AndroidApiSupport.status(sdk))
            assertNull(AndroidApiSupport.unsupportedReason(sdk))
            assertTrue(AndroidApiSupport.isSupported(sdk))
        }
    }

    @Test
    fun newerApisRemainSupportedButUnverified() {
        assertEquals(AndroidApiSupport.Status.UNVERIFIED, AndroidApiSupport.status(38))
        assertNull(AndroidApiSupport.unsupportedReason(38))
        assertTrue(AndroidApiSupport.isSupported(38))
    }
}
