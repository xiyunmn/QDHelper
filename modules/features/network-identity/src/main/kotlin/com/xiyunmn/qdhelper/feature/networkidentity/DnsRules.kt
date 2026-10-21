package com.xiyunmn.qdhelper.feature.networkidentity

import android.system.Os
import android.system.OsConstants
import java.net.InetAddress
import java.util.Locale

internal class DnsRules private constructor(
    private val mapping: Map<String, List<InetAddress>>,
) {
    private val suffixes = mapping.keys.sortedByDescending(String::length)

    fun isEmpty(): Boolean = mapping.isEmpty()
    fun hosts(): List<String> = mapping.keys.toList()

    fun addressesFor(hostname: String?): List<InetAddress> {
        val host = normalize(hostname.orEmpty())
        val addresses = mapping[host] ?: suffixes.firstOrNull { host.endsWith(".$it") }?.let(mapping::get)
        // Host callers must not mutate the cached rule set.
        return addresses?.let(::ArrayList).orEmpty()
    }

    companion object {
        private data class Cached(val raw: String, val rules: DnsRules)
        @Volatile private var cached: Cached? = null

        fun parse(raw: String): DnsRules {
            cached?.takeIf { it.raw == raw }?.let { return it.rules }
            return synchronized(this) {
                cached?.takeIf { it.raw == raw }?.let { return@synchronized it.rules }
                val mapping = linkedMapOf<String, List<InetAddress>>()
                raw.lineSequence().forEach { rawLine ->
                    val line = rawLine.substringBefore('#').trim()
                    val split = line.indexOf('=').takeIf { it >= 0 } ?: line.indexOfFirst(Char::isWhitespace)
                    if (split <= 0) return@forEach
                    val host = normalize(line.substring(0, split))
                    if (host.isEmpty() || host.any { it.isWhitespace() || it in "/*:=[]" }) return@forEach
                    val addresses = line.substring(split + 1).split(Regex("[,;\\s]+"))
                        .filter(String::isNotBlank)
                        .mapNotNull { literal ->
                            // inet_pton parses numeric literals only; never enter the DNS resolver.
                            runCatching {
                                Os.inet_pton(OsConstants.AF_INET, literal)
                                    ?: Os.inet_pton(OsConstants.AF_INET6, literal)
                            }.getOrNull()
                        }.distinct()
                    if (addresses.isNotEmpty()) mapping[host] = addresses
                }
                DnsRules(mapping).also { cached = Cached(raw, it) }
            }
        }

        private fun normalize(host: String): String = host.trim().trimEnd('.').lowercase(Locale.ROOT)
    }
}
