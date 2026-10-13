package com.xiyunmn.qdhelper.config

object RuntimeStateMigrationV1 {
    internal const val MARKER = "migration.runtime_state.v1.complete"

    private val stringKeys = listOf(
        SettingIds.KEY_PAGE_CUSTOM_PENDING_SCAN,
        SettingIds.KEY_AUTO_SIGN_IN_LAST_DATE,
        SettingIds.KEY_CUSTOM_QIMEI_LAST_SYNCED,
        SettingIds.KEY_CUSTOM_QIMEI36_LAST_SYNCED,
    )
    private const val LONG_KEY = SettingIds.KEY_AUTO_SIGN_IN_LAST_USER

    fun migrate(): Boolean {
        val (legacy, runtime) = ConfigRuntime.stores() ?: return false
        return migrate(SharedPreferencesMigrationStore(legacy), SharedPreferencesMigrationStore(runtime))
    }

    internal fun migrate(legacy: MigrationStore, runtime: MigrationStore): Boolean {
        if (runtime.readBoolean(MARKER, false)) return true

        stringKeys.forEach { rawKey ->
            if (!runtime.contains(rawKey) && legacy.contains(rawKey)) {
                val value = legacy.readString(rawKey, "")
                if (!runtime.writeString(rawKey, value) || runtime.readString(rawKey, "") != value) return false
            }
        }
        if (!runtime.contains(LONG_KEY) && legacy.contains(LONG_KEY)) {
            val value = legacy.readLong(LONG_KEY, Long.MIN_VALUE)
            if (!runtime.writeLong(LONG_KEY, value) || runtime.readLong(LONG_KEY, Long.MIN_VALUE) != value) return false
        }

        val migratedKeys = stringKeys + LONG_KEY
        if (migratedKeys.any { legacy.contains(it) && !runtime.contains(it) }) return false
        val legacyKeys = migratedKeys.filter(legacy::contains)
        if (legacyKeys.isNotEmpty() && (!legacy.removeAll(legacyKeys) || legacyKeys.any(legacy::contains))) return false
        return runtime.writeBoolean(MARKER, true) && runtime.readBoolean(MARKER, false)
    }
}

internal interface MigrationStore {
    fun contains(rawKey: String): Boolean
    fun readBoolean(rawKey: String, defaultValue: Boolean): Boolean
    fun readString(rawKey: String, defaultValue: String): String
    fun readLong(rawKey: String, defaultValue: Long): Long
    fun writeBoolean(rawKey: String, value: Boolean): Boolean
    fun writeString(rawKey: String, value: String): Boolean
    fun writeLong(rawKey: String, value: Long): Boolean
    fun removeAll(rawKeys: Collection<String>): Boolean
}

private class SharedPreferencesMigrationStore(
    private val store: SharedPreferencesSettingsStore,
) : MigrationStore {
    override fun contains(rawKey: String): Boolean = store.contains(rawKey)
    override fun readBoolean(rawKey: String, defaultValue: Boolean): Boolean =
        store.readRawBoolean(rawKey, defaultValue)
    override fun readString(rawKey: String, defaultValue: String): String =
        store.readRawString(rawKey, defaultValue)
    override fun readLong(rawKey: String, defaultValue: Long): Long =
        store.readRawLong(rawKey, defaultValue)
    override fun writeBoolean(rawKey: String, value: Boolean): Boolean =
        store.writeRawBoolean(rawKey, value)
    override fun writeString(rawKey: String, value: String): Boolean =
        store.writeRawString(rawKey, value)
    override fun writeLong(rawKey: String, value: Long): Boolean =
        store.writeRawLong(rawKey, value)
    override fun removeAll(rawKeys: Collection<String>): Boolean = store.removeRaw(rawKeys)
}
