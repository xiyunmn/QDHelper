package com.xiyunmn.qdhelper.feature.account

import android.content.Context
import android.util.AtomicFile
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/** Persist before dispatch: process death or reload must not turn an unknown POST into a retry. */
internal object AccountRequestLedger {
    fun reserveSignIn(context: Context, userId: Long, day: String): Boolean =
        update(context, userId, day) { state ->
            val attempts = state.getJSONObject("attempts")
            if (state.optString("stopReason").isNotBlank()) return@update false
            if (attempts.has("request")) return@update false
            attempts.put("request", System.currentTimeMillis())
            true
        }

    /** A daily opt-out is separate from a sent request and never marks the host checked in. */
    fun stopSignIn(context: Context, userId: Long, day: String, reason: String): Boolean =
        update(context, userId, day) { state -> state.put("stopReason", reason); true }

    @Synchronized
    fun signInStopReason(context: Context, userId: Long, day: String): String? {
        require(userId > 0)
        val directory = File(context.filesDir, "qdhelper/account-requests")
        if (!directory.exists()) return null
        RandomAccessFile(File(directory, "sign-in-$userId.lock"), "rw").use { handle ->
            val lock = checkNotNull(handle.channel.tryLock()) { "Sign-in journal busy" }
            lock.use {
                val file = AtomicFile(File(directory, "sign-in-$userId.json"))
                if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
                val state = JSONObject(file.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
                check(state.getInt("schema") == 1)
                val savedDay = state.getString("day")
                val attempts = state.getJSONObject("attempts")
                if (day < savedDay) return "clock-rollback"
                if (day != savedDay) return null
                return state.optString("stopReason").takeIf { it.isNotBlank() }
                    ?: if (attempts.has("request")) "request-reserved" else null
            }
        }
    }

    @Synchronized
    private fun update(context: Context, userId: Long, day: String, change: (JSONObject) -> Boolean): Boolean =
        runCatching {
            require(userId > 0)
            val directory = File(context.filesDir, "qdhelper/account-requests")
            check(directory.isDirectory || directory.mkdirs())
            RandomAccessFile(File(directory, "sign-in-$userId.lock"), "rw").use { handle ->
                val lock = handle.channel.tryLock() ?: return@use false
                lock.use locked@{
                    val file = AtomicFile(File(directory, "sign-in-$userId.json"))
                    val existing = if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
                        JSONObject(file.openRead().use { it.readBytes().toString(Charsets.UTF_8) }).also {
                            check(it.getInt("schema") == 1)
                            it.getJSONObject("attempts")
                            it.getString("day")
                        }
                    } else null
                    // Clock rollback must not erase a later day's dispatch journal.
                    if (existing != null && day < existing.getString("day")) return@locked false
                    val state = existing?.takeIf { it.getString("day") == day }
                        ?: JSONObject().put("schema", 1).put("day", day).put("attempts", JSONObject())
                    if (!change(state)) return@locked false
                    val bytes = state.toString().toByteArray(Charsets.UTF_8)
                    val output = file.startWrite()
                    try {
                        output.write(bytes)
                        file.finishWrite(output)
                    } catch (failure: Throwable) {
                        file.failWrite(output)
                        throw failure
                    }
                    check(file.openRead().use { it.readBytes() }.contentEquals(bytes)) { "Sign-in journal readback failed" }
                    true
                }
            }
        }.onFailure { FeatureHooks.w("Account request journal unavailable: family=sign-in, error=${it.javaClass.simpleName}") }
            .getOrDefault(false)
}
