package com.xiyunmn.qdhelper.foundation.identity

@JvmInline
value class OwnerId(val value: String) {
    init {
        require(ID_PATTERN.matches(value)) { "Invalid owner ID: $value" }
    }

    override fun toString(): String = value

    private companion object {
        val ID_PATTERN = Regex("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*")
    }
}
