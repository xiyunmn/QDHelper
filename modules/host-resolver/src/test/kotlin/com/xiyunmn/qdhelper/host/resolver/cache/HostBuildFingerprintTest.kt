package com.xiyunmn.qdhelper.host.resolver.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HostBuildFingerprintTest {
    @Test
    fun hashIsStableAcrossApkInputOrderAndChangesWithCatalog() {
        fun fingerprint(parts: List<HostBuildFingerprint.ApkPart>, catalog: String) = HostBuildFingerprint(
            versionName = "7.9.469",
            versionCode = 79469,
            lastUpdateTime = 1234,
            apkParts = parts,
            processName = "com.qidian.QDReader",
            resolverSchema = 1,
            dexKitVersion = "2.2.0",
            catalogSha256 = catalog,
        )
        val base = HostBuildFingerprint.ApkPart("base.apk", 10, 20)
        val split = HostBuildFingerprint.ApkPart("split.apk", 30, 40)

        assertEquals(fingerprint(listOf(base, split), "a").sha256(), fingerprint(listOf(split, base), "a").sha256())
        assertNotEquals(fingerprint(listOf(base), "a").sha256(), fingerprint(listOf(base), "b").sha256())
        assertEquals(fingerprint(listOf(base), "a").cacheSha256(), fingerprint(listOf(base), "b").cacheSha256())
        assertEquals(fingerprint(listOf(base, split), "a").cacheSha256(), fingerprint(listOf(split, base), "b").cacheSha256())
    }

    @Test
    fun cacheIdentityStillSeparatesEveryHostAndResolverBoundary() {
        val original = HostBuildFingerprint(
            versionName = "7.9.470",
            versionCode = 79470,
            lastUpdateTime = 1234,
            apkParts = listOf(HostBuildFingerprint.ApkPart("base.apk", 10, 20)),
            processName = "com.qidian.QDReader",
            resolverSchema = 2,
            dexKitVersion = "2.2.0",
            catalogSha256 = "catalog",
        )
        val changed = listOf(
            original.copy(versionName = "7.9.472"),
            original.copy(versionCode = 79472),
            original.copy(lastUpdateTime = 5678),
            original.copy(apkParts = listOf(HostBuildFingerprint.ApkPart("base.apk", 11, 20))),
            original.copy(apkParts = listOf(HostBuildFingerprint.ApkPart("base.apk", 10, 21))),
            original.copy(apkParts = original.apkParts + HostBuildFingerprint.ApkPart("split.apk", 1, 2)),
            original.copy(processName = "com.qidian.QDReader:mini"),
            original.copy(resolverSchema = 3),
            original.copy(dexKitVersion = "next"),
        )
        changed.forEach { assertNotEquals(original.cacheSha256(), it.cacheSha256()) }
    }
}
