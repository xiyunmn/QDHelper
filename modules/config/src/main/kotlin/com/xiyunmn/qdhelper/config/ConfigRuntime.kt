package com.xiyunmn.qdhelper.config

import android.content.Context
import com.xiyunmn.qdhelper.config.schema.SettingSchemaCatalog
import kotlin.reflect.KClass

object ConfigRuntime {
    private const val USER_PREFS = SettingIds.PREFS_NAME
    private const val RUNTIME_PREFS = "qdhelper_runtime_state"

    @Volatile
    private var userStore: SharedPreferencesSettingsStore? = null

    @Volatile
    private var runtimeStore: SharedPreferencesSettingsStore? = null

    @Volatile
    private var initializedPackageName: String? = null

    private val initializationLock = Any()

    fun initialize(context: Context) {
        val appContext = context.applicationContext ?: context
        if (initializedPackageName == appContext.packageName && userStore != null && runtimeStore != null) return
        synchronized(initializationLock) {
            if (initializedPackageName == appContext.packageName && userStore != null && runtimeStore != null) return
            userStore = SharedPreferencesSettingsStore(
                appContext.getSharedPreferences(USER_PREFS, Context.MODE_PRIVATE),
            )
            runtimeStore = SharedPreferencesSettingsStore(
                appContext.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE),
            )
            initializedPackageName = appContext.packageName
        }
    }

    fun clear() {
        synchronized(initializationLock) {
            initializedPackageName = null
            userStore = null
            runtimeStore = null
        }
    }

    fun readBoolean(rawKey: String): Boolean = read(rawKey, Boolean::class)

    fun readString(rawKey: String): String = read(rawKey, String::class)

    fun readLong(rawKey: String): Long = read(rawKey, Long::class)

    fun putBoolean(rawKey: String, value: Boolean): Boolean = put(rawKey, Boolean::class, value)

    fun putString(rawKey: String, value: String): Boolean = put(rawKey, String::class, value)

    fun putLong(rawKey: String, value: Long): Boolean = put(rawKey, Long::class, value)

    /** Includes direct writes, archive imports and resets. Keep the returned registration alive. */
    fun observeUserChanges(context: Context, keys: Set<String>, changed: () -> Unit): AutoCloseable {
        initialize(context)
        return requireNotNull(userStore).observe(keys, changed)
    }

    fun putRuntimeValues(values: Map<String, Any>): Boolean {
        values.forEach { (rawKey, value) ->
            val key = SettingSchemaCatalog.requireKey(rawKey)
            require(key.scope == SettingScope.RUNTIME) { "Setting is not runtime scoped: $rawKey" }
            require(key.accepts(value)) { "Invalid value for setting $rawKey" }
        }
        return runtimeStore?.putRaw(values) ?: false
    }

    internal fun resetDefaults(rawKeys: Collection<String>): Boolean {
        val keys = rawKeys.distinct().map(SettingSchemaCatalog::requireKey)
        val userKeys = keys.filter { it.scope != SettingScope.RUNTIME }
        val runtimeKeys = keys.filter { it.scope == SettingScope.RUNTIME }
        return (userKeys.isEmpty() || userStore?.putDefaults(userKeys) == true) &&
            (runtimeKeys.isEmpty() || runtimeStore?.putDefaults(runtimeKeys) == true)
    }

    internal fun stores(): Pair<SharedPreferencesSettingsStore, SharedPreferencesSettingsStore>? {
        val user = userStore ?: return null
        val runtime = runtimeStore ?: return null
        return user to runtime
    }

    private fun <T : Any> read(rawKey: String, type: KClass<T>): T {
        val key = typedKey(rawKey, type)
        return storeFor(key.scope)?.get(key) ?: key.defaultValue
    }

    private fun <T : Any> put(rawKey: String, type: KClass<T>, value: T): Boolean {
        val key = typedKey(rawKey, type)
        return storeFor(key.scope)?.put(key, value) ?: false
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> typedKey(rawKey: String, type: KClass<T>): SettingKey<T> {
        val key = SettingSchemaCatalog.requireKey(rawKey)
        require(key.valueType == type) { "Setting $rawKey has type ${key.valueType}, requested $type" }
        return key as SettingKey<T>
    }

    private fun storeFor(scope: SettingScope): SharedPreferencesSettingsStore? =
        if (scope == SettingScope.RUNTIME) runtimeStore else userStore
}
