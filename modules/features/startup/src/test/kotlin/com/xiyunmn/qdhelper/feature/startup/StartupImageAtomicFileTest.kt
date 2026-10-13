package com.xiyunmn.qdhelper.feature.startup

import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class StartupImageAtomicFileTest {
    private val target = File(RuntimeEnvironment.getApplication().filesDir, "splash.jpg")
    private val previous = byteArrayOf(1, 2, 3)

    @Test fun copyFailureLeavesThePreviousCompleteFileAvailable() {
        target.writeBytes(previous)
        assertThrows(IOException::class.java) {
            StartupImageStore.writeAtomically(target, { true }) { output ->
                output.write(byteArrayOf(4, 5))
                assertArrayEquals(previous, target.readBytes())
                throw IOException("Source read failed")
            }
        }
        assertArrayEquals(previous, target.readBytes())
        val replacement = ByteArray(8192) { (it % 127).toByte() }
        StartupImageStore.writeAtomically(target, { true }) { it.write(replacement) }
        assertArrayEquals(replacement, target.readBytes())
    }

    @Test fun anOldCancelledWriterCannotDeleteTheReplacementWritersTemporaryFile() {
        target.writeBytes(previous)
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val valid = AtomicBoolean(true)
        val firstFailure = AtomicReference<Throwable>()
        val secondFailure = AtomicReference<Throwable>()
        val replacement = ByteArray(8192) { 7 }
        val first = Thread {
            firstFailure.set(runCatching {
                StartupImageStore.writeAtomically(target, valid::get) { output ->
                    output.write(4)
                    firstEntered.countDown()
                    releaseFirst.await()
                }
            }.exceptionOrNull())
        }
        val second = Thread {
            secondFailure.set(runCatching {
                StartupImageStore.writeAtomically(target, { true }) { output ->
                    secondEntered.countDown()
                    output.write(replacement)
                }
            }.exceptionOrNull())
        }
        try {
            first.start()
            assertTrue(firstEntered.await(3, TimeUnit.SECONDS))
            valid.set(false)
            second.start()
            assertFalse(secondEntered.await(100, TimeUnit.MILLISECONDS))
            assertArrayEquals(previous, target.readBytes())
            releaseFirst.countDown()
            first.join(3_000)
            second.join(3_000)
            assertFalse(first.isAlive || second.isAlive)
            assertTrue(firstFailure.get() is CancellationException)
            assertNull(secondFailure.get())
            assertArrayEquals(replacement, target.readBytes())
        } finally { releaseFirst.countDown() }
    }
}
