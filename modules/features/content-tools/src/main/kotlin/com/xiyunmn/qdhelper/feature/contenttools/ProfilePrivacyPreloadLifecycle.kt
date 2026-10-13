package com.xiyunmn.qdhelper.feature.contenttools

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal class ProfilePrivacyPreloadLifecycle {
    internal data class Token(
        val userId: Long,
        val lifecycleGeneration: Long,
        val userGeneration: Long,
    )

    private val lifecycleGeneration = AtomicLong(0L)
    private val userGenerations = ConcurrentHashMap<Long, AtomicLong>()

    fun capture(userId: Long): Token = Token(
        userId = userId,
        lifecycleGeneration = lifecycleGeneration.get(),
        userGeneration = userGenerations[userId]?.get() ?: 0L,
    )

    fun isCurrent(token: Token): Boolean =
        token.lifecycleGeneration == lifecycleGeneration.get() &&
            token.userGeneration == (userGenerations[token.userId]?.get() ?: 0L)

    fun invalidateUser(userId: Long) {
        userGenerations.computeIfAbsent(userId) { AtomicLong(0L) }.incrementAndGet()
    }

    fun invalidateAll() {
        lifecycleGeneration.incrementAndGet()
    }

    fun clearUserGenerations() {
        userGenerations.clear()
    }
}
