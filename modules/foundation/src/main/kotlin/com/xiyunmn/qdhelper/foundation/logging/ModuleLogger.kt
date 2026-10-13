package com.xiyunmn.qdhelper.foundation.logging

enum class LogLevel {
    INFO,
    WARN,
    ERROR,
}

data class LogEvent(
    val level: LogLevel,
    val message: String,
    val throwable: Throwable? = null,
)

fun interface LogSink {
    fun emit(event: LogEvent)
}

interface ModuleLogger {
    fun info(message: String)
    fun warn(message: String)
    fun error(message: String, throwable: Throwable? = null)
}
