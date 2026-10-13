package com.xiyunmn.qdhelper.config

import kotlin.reflect.KClass

enum class SettingScope {
    USER,
    DEBUG,
    RUNTIME,
}

class SettingKey<T : Any>(
    val rawKey: String,
    val valueType: KClass<T>,
    val defaultValue: T,
    val scope: SettingScope,
    val validate: (T) -> Boolean = { true },
) {
    init {
        require(KEY_PATTERN.matches(rawKey)) { "Invalid setting key: $rawKey" }
        require(validate(defaultValue)) { "Invalid default for setting $rawKey" }
    }

    override fun equals(other: Any?): Boolean = other is SettingKey<*> && rawKey == other.rawKey
    override fun hashCode(): Int = rawKey.hashCode()
    override fun toString(): String = rawKey

    @Suppress("UNCHECKED_CAST")
    fun accepts(value: Any): Boolean = valueType.isInstance(value) && validate(value as T)

    private companion object {
        val KEY_PATTERN = Regex("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*")
    }
}
