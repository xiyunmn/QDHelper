package com.xiyunmn.qdhelper.foundation.logging

/** Process-wide logger endpoint configured by the application composition root. */
object RuntimeLog {
    @Volatile
    private var logger: ModuleLogger? = null

    fun configure(value: ModuleLogger) {
        logger = value
    }

    fun clear(value: ModuleLogger? = null) {
        if (value == null || logger === value) logger = null
    }

    fun i(message: String) = logger?.info(message) ?: Unit
    fun w(message: String) = logger?.warn(message) ?: Unit
    fun e(message: String, throwable: Throwable? = null) = logger?.error(message, throwable) ?: Unit
}
