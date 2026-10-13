package com.xiyunmn.qdhelper.host.resolver.cache

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import com.xiyunmn.qdhelper.host.resolver.catalog.HostTargetCatalog
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolutionCacheTest {
    private val specs = HostTargetCatalog.forProcess(HostSymbols.PACKAGE).take(2)

    @Test
    fun revisingOneTargetInvalidatesOnlyItsOwnPositiveOrNegativeEntry() {
        val cache = ResolutionCache(FakeContext())
        val first = specs[0]
        val other = specs[1]
        assertTrue(cache.write(first, "host", listOf("first-method")))
        assertTrue(cache.write(other, "host", listOf("other-method")))
        assertNull(cache.read(first.copy(revision = first.revision + 1), "host"))
        assertEquals(listOf("other-method"), cache.read(other, "host"))
        cache.writeNegative(first, "host")
        assertTrue(cache.isNegative(first, "host"))
        assertFalse(cache.isNegative(first.copy(revision = first.revision + 1), "host"))
        assertEquals(listOf("other-method"), cache.read(other, "host"))
        assertNull(cache.read(other, "different-host"))
    }

    @Test
    fun migrationRemovesOldEntriesAndBootstrapFlagsWithoutDeletingCurrentData() {
        val context = FakeContext()
        val preferences = context.getSharedPreferences("qdhelper_host_resolution_cache", 0)
        preferences.edit()
            .putString("target.legacy.r1.old", "miss=true")
            .putString("target.v2.keep.r1.host", "preserve")
            .putBoolean("meta.prewarm.full_catalog_attempted.v1.old", true)
            .putBoolean("meta.v2.prewarm.attempted.current", true)
            .putBoolean("meta.legacy_settings_cache_cleaned.v1", true)
            .putString("unrelated", "preserve")
            .apply()
        val cache = ResolutionCache(context)

        assertFalse(preferences.all.containsKey("target.legacy.r1.old"))
        assertFalse(preferences.all.containsKey("meta.prewarm.full_catalog_attempted.v1.old"))
        assertEquals("preserve", preferences.getString("target.v2.keep.r1.host", null))
        assertEquals("preserve", preferences.getString("unrelated", null))
        assertTrue(preferences.getBoolean("meta.legacy_settings_cache_cleaned.v1", false))
        assertTrue(cache.readFlag("prewarm.attempted", "current"))
        assertFalse(cache.readFlag("prewarm.attempted", "changed-catalog"))
        assertTrue(cache.writeFlag("prewarm.attempted", "changed-catalog", true))
        assertTrue(cache.readFlag("prewarm.attempted", "changed-catalog"))
    }

    @Test
    fun invalidPayloadIsDiscardedAndRescannedValuesReplaceNegativeEntries() {
        val context = FakeContext()
        val cache = ResolutionCache(context)
        val target = specs.first()
        cache.writeNegative(target, "host")
        assertTrue(cache.isNegative(target, "host"))
        assertTrue(cache.write(target, "host", listOf("verified")))
        assertFalse(cache.isNegative(target, "host"))
        assertEquals(listOf("verified"), cache.read(target, "host"))
        val preferences = context.getSharedPreferences("qdhelper_host_resolution_cache", 0)
        val key = preferences.all.keys.single { it.startsWith("target.v2.") }
        preferences.edit().putString(key, "count=2\nonly-one\n").apply()
        assertNull(cache.read(target, "host"))
        assertFalse(preferences.all.containsKey(key))
    }

    private class FakeContext : ContextWrapper(null) {
        private val stores = HashMap<String, SharedPreferences>()

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            stores.getOrPut(name) { preferences() }

        private fun preferences(): SharedPreferences {
            val values = HashMap<String, Any>()
            return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { proxy, method, args ->
                val key = args?.firstOrNull() as? String
                when (method.name) {
                    "getAll" -> values.toMap()
                    "getString", "getBoolean" -> values[key] ?: args?.get(1)
                    "edit" -> editor(values)
                    "toString" -> "MemoryPreferences"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> error("Unexpected preferences call: ${method.name}")
                }
            } as SharedPreferences
        }

        private fun editor(values: MutableMap<String, Any>): SharedPreferences.Editor {
            val changes = LinkedHashMap<String, Any?>()
            return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
                when (method.name) {
                    "putString", "putBoolean" -> {
                        changes[args!![0] as String] = args[1]
                        proxy
                    }
                    "remove" -> {
                        changes[args!![0] as String] = null
                        proxy
                    }
                    "apply", "commit" -> {
                        changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                        if (method.name == "commit") true else null
                    }
                    else -> error("Unexpected editor call: ${method.name}")
                }
            } as SharedPreferences.Editor
        }
    }
}
