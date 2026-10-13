package com.xiyunmn.qdhelper.hook.concurrency

import com.xiyunmn.qdhelper.hook.resource.RuntimeCleanupRegistry
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ManagedExecutor(
    private val ownerKey: String,
    private val threadName: String,
    private val threadCount: Int = 1,
    private val onFailure: (Throwable) -> Unit = {},
    private val onTerminated: () -> Unit = {},
) : AutoCloseable {
    private val lock = Any()
    private var executor: ThreadPoolExecutor? = null
    private var cleanup: AutoCloseable? = null
    private val tasks = mutableSetOf<FutureTask<*>>()
    private val terminationDelivered = AtomicBoolean(false)

    @Volatile
    var isClosed: Boolean = false
        private set

    init {
        require(threadCount > 0)
    }

    /** False means the owner has already closed; no task or replacement pool is created. */
    fun execute(task: () -> Unit): Boolean = enqueue(ManagedTask(task))

    fun <T> submit(task: () -> T): Future<T> = ManagedTask(task).also(::enqueue)

    private fun enqueue(task: FutureTask<*>): Boolean = synchronized(lock) {
        if (isClosed) {
            task.cancel(false)
            return false
        }
        val service = executor ?: create().also { created ->
            executor = created
            cleanup = RuntimeCleanupRegistry.register("managed-executor:$ownerKey", ::close)
        }
        // Registration can close us immediately while a runtime cleanup is in progress.
        if (isClosed) {
            task.cancel(false)
            return false
        }
        tasks += task
        try {
            service.execute(task)
            true
        } catch (rejected: RejectedExecutionException) {
            task.cancel(false)
            if (!isClosed) reportFailure(rejected)
            false
        }
    }

    override fun close() {
        val (closing, cancelling) = synchronized(lock) {
            if (isClosed) return
            isClosed = true
            val current = executor to tasks.toList()
            executor = null
            cleanup?.close()
            cleanup = null
            current
        }
        // shutdownNow alone leaves queued FutureTasks permanently incomplete.
        cancelling.forEach { it.cancel(true) }
        if (closing == null) deliverTermination() else closing.shutdownNow()
    }

    private inner class ManagedTask<T>(task: () -> T) : FutureTask<T>(Callable(task)) {
        override fun done() {
            synchronized(lock) { tasks.remove(this) }
            if (isCancelled) return
            try {
                get()
            } catch (failure: ExecutionException) {
                reportFailure(failure.cause ?: failure)
            } catch (_: CancellationException) {
                // Cancellation is a lifecycle event, not a worker failure.
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun reportFailure(throwable: Throwable) {
        if (throwable is CancellationException || throwable is InterruptedException) return
        runCatching { onFailure(throwable) }
    }

    private fun deliverTermination() {
        if (terminationDelivered.compareAndSet(false, true)) {
            runCatching(onTerminated).onFailure(::reportFailure)
        }
    }

    private fun create(): ThreadPoolExecutor {
        val sequence = AtomicInteger()
        val factory = ThreadFactory { runnable ->
            val suffix = if (threadCount == 1) "" else "-${sequence.incrementAndGet()}"
            Thread(runnable, "$threadName$suffix").apply {
                isDaemon = true
                uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, throwable -> reportFailure(throwable) }
            }
        }
        return object : ThreadPoolExecutor(threadCount, threadCount, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(), factory) {
            override fun terminated() {
                // Running native work can ignore interruption. Release its resources only here.
                deliverTermination()
            }
        }
    }
}
