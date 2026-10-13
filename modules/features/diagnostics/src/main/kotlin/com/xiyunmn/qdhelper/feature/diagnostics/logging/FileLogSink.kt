package com.xiyunmn.qdhelper.feature.diagnostics.logging

import com.xiyunmn.qdhelper.feature.api.runtime.FeatureResources

import android.content.Context
import com.xiyunmn.qdhelper.foundation.logging.AndroidLog
import com.xiyunmn.qdhelper.foundation.logging.LogEvent
import com.xiyunmn.qdhelper.foundation.logging.LogLevel
import com.xiyunmn.qdhelper.foundation.logging.LogSink
import com.xiyunmn.qdhelper.hook.concurrency.ManagedExecutor
import com.xiyunmn.qdhelper.shared.io.readBytesBounded
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 由详细日志总开关控制，将已脱敏的日志行写入宿主私有目录
 * files/qdhelper/logs/qdhelper.log，超过阈值后轮转并保留 .1 到 .3 备份。
 *
 * 约束：
 * - 只接收已经过 [SafeLog.sanitize] 的字符串，绝不落盘 Cookie/token/账号/设备标识等敏感值。
 * - 文件写入、导出和清理在后台执行，并通过同一把锁协调。
 * - 热重载时通过 [FeatureResources] 停线程并释放句柄。
 */
object FileLogSink : LogSink {
    private const val TAG = "QDHelper"
    private const val LOG_DIR = "qdhelper/logs"
    private const val LOG_FILE = "qdhelper.log"
    private const val MAX_FILE_BYTES = 1024L * 1024L
    private const val MAX_BACKUP_FILES = 3
    private const val CLEANUP_KEY = "core.file_log_sink"

    @Volatile
    private var enabled = false

    private val lock = Any()
    private var executor: ManagedExecutor? = null
    private var logDir: File? = null
    private var cleanup: AutoCloseable? = null
    private var generation = 0L

    private val timestampFormat = ThreadLocal.withInitial {
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    }

    /** 依据当前设置开启或关闭文件日志。在宿主 Context 初始化后调用，幂等。 */
    fun configure(context: Context, enabled: Boolean) {
        if (enabled) {
            enable(context)
        } else {
            disable()
        }
    }

    override fun emit(event: LogEvent) {
        val (exec, epoch) = synchronized(lock) {
            val worker = if (enabled) executor else null
            if (worker == null) return
            worker to generation
        }
        val line = format(event.level, event.message)
        exec.execute {
            synchronized(lock) {
                if (enabled && executor === exec && generation == epoch) writeLine(line)
            }
        }
    }

    /** Run on a worker; only known log files are exported, never other host files. */
    fun archive(context: Context): ByteArray = synchronized(lock) {
        val files = logFiles(context).filter { it.isFile }
        check(files.isNotEmpty()) { "暂无日志，请先开启详细日志并重启后复现问题" }
        val bytes = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bytes).use { zip ->
            files.forEach { file ->
                zip.putNextEntry(java.util.zip.ZipEntry(file.name))
                file.inputStream().use { zip.write(it.readBytesBounded(2 * MAX_FILE_BYTES)) }
                zip.closeEntry()
            }
        }
        bytes.toByteArray()
    }

    /** The generation prevents already queued lines from recreating cleared logs. */
    fun clearLogs(context: Context): Int = synchronized(lock) {
        generation++
        var deleted = 0
        logFiles(context).filter { it.exists() }.forEach { file ->
            check(file.delete()) { "部分日志无法清理，请重试" }
            deleted++
        }
        deleted
    }

    private fun logFiles(context: Context): List<File> {
        val directory = File(context.filesDir, LOG_DIR)
        return (MAX_BACKUP_FILES downTo 0).map { index ->
            File(directory, if (index == 0) LOG_FILE else "$LOG_FILE.$index")
        }
    }

    private fun enable(context: Context) {
        synchronized(lock) {
            if (enabled) {
                return
            }
            val dir = File(context.filesDir, LOG_DIR)
            runCatching { dir.mkdirs() }
            logDir = dir
            executor = ManagedExecutor(
                ownerKey = CLEANUP_KEY,
                threadName = "qdhelper-filelog",
                onFailure = { throwable -> AndroidLog.warn(TAG, "File log worker failed", throwable) },
            )
            enabled = true
            cleanup = FeatureResources.register(CLEANUP_KEY) { disable() }
            if (enabled) AndroidLog.info(TAG, "File log enabled: ${dir.absolutePath}")
        }
    }

    private fun disable() {
        synchronized(lock) {
            if (!enabled && executor == null) {
                return
            }
            enabled = false
            cleanup?.let { runCatching { it.close() } }
            cleanup = null
            logDir = null
            val exec = executor
            executor = null
            exec?.close()
        }
    }

    private fun writeLine(line: String) {
        val dir = logDir ?: return
        runCatching {
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val file = File(dir, LOG_FILE)
            if (file.exists() && file.length() >= MAX_FILE_BYTES) {
                rotate(dir, file)
            }
            file.appendText(line + "\n")
        }.onFailure { throwable ->
            // 落盘失败只写 logcat，避免递归触发文件日志。
            AndroidLog.warn(TAG, "File log write failed: ${throwable.javaClass.name}")
        }
    }

    private fun rotate(dir: File, current: File) {
        for (index in MAX_BACKUP_FILES downTo 1) {
            val target = File(dir, "$LOG_FILE.$index")
            if (index == MAX_BACKUP_FILES && target.exists()) {
                target.delete()
            }
            val source = if (index == 1) {
                current
            } else {
                File(dir, "$LOG_FILE.${index - 1}")
            }
            if (source.exists()) {
                source.renameTo(target)
            }
        }
    }

    private fun format(level: LogLevel, message: String): String {
        val timestamp = timestampFormat.get()?.format(Date()).orEmpty()
        return "$timestamp ${levelChar(level)} $message"
    }

    private fun levelChar(level: LogLevel): Char = when (level) {
        LogLevel.WARN -> 'W'
        LogLevel.ERROR -> 'E'
        else -> 'I'
    }
}
