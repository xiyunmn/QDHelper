package com.xiyunmn.qdhelper.config

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ConfigurationArchiveCodecTest {
    @Test
    fun `archive round trip verifies manifest payloads without self hash`() {
        val files = ConfigurationArchiveFiles(
            settingsJson = """{"format":"qdhelper-settings","version":1,"settings":{"module.enabled":true}}""",
            bookShieldYaml = BookShieldYamlCodec.template(BookShieldRuleConfig(authors = listOf("作者甲"))),
        )

        val decoded = ConfigurationArchiveCodec.decode(ConfigurationArchiveCodec.encode(files))

        assertEquals(files, decoded)
    }

    @Test
    fun `legacy json and unknown archive version are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            ConfigurationArchiveCodec.decode("{\"version\":1}".toByteArray())
        }
        val entries = unzip(ConfigurationArchiveCodec.encode(validFiles())).toMutableMap()
        val manifest = JSONObject(entries.getValue("manifest.json").toString(Charsets.UTF_8))
            .put("version", ConfigurationArchiveCodec.FORMAT_VERSION + 1)
        entries["manifest.json"] = manifest.toString().toByteArray()

        assertThrows(IllegalArgumentException::class.java) {
            ConfigurationArchiveCodec.decode(zip(entries.entries.map { it.toPair() }))
        }

        val stringVersionEntries = unzip(ConfigurationArchiveCodec.encode(validFiles())).toMutableMap()
        val stringVersion = JSONObject(stringVersionEntries.getValue("manifest.json").toString(Charsets.UTF_8))
            .put("version", "1")
        stringVersionEntries["manifest.json"] = stringVersion.toString().toByteArray()
        assertThrows(IllegalStateException::class.java) {
            ConfigurationArchiveCodec.decode(zip(stringVersionEntries.entries.map { it.toPair() }))
        }
    }

    @Test
    fun `manifest numeric fields do not accept string coercion`() {
        val entries = unzip(ConfigurationArchiveCodec.encode(validFiles())).toMutableMap()
        val manifest = JSONObject(entries.getValue("manifest.json").toString(Charsets.UTF_8))
        val first = manifest.getJSONArray("entries").getJSONObject(0)
        first.put("size", first.getLong("size").toString())
        entries["manifest.json"] = manifest.toString().toByteArray()

        assertThrows(IllegalStateException::class.java) {
            ConfigurationArchiveCodec.decode(zip(entries.entries.map { it.toPair() }))
        }
    }

    @Test
    fun `duplicate entries and extra paths are rejected`() {
        val encoded = ConfigurationArchiveCodec.encode(validFiles())
        val valid = unzip(encoded)
        val duplicate = replaceAscii(encoded, "manifest.json", "settings.json")
        assertThrows(IllegalArgumentException::class.java) {
            ConfigurationArchiveCodec.decode(duplicate)
        }
        val extra = valid.entries.map { it.toPair() } + ("../outside" to byteArrayOf(1))
        assertThrows(IllegalArgumentException::class.java) {
            ConfigurationArchiveCodec.decode(zip(extra))
        }
    }

    @Test
    fun `manifest hash mismatch and expanded entry limit are rejected`() {
        val entries = unzip(ConfigurationArchiveCodec.encode(validFiles())).toMutableMap()
        entries["settings.json"] = entries.getValue("settings.json") + byteArrayOf(' '.code.toByte())
        assertThrows(IllegalArgumentException::class.java) {
            ConfigurationArchiveCodec.decode(zip(entries.entries.map { it.toPair() }))
        }

        val oversized = ByteArray(ConfigurationArchiveCodec.MAX_ENTRY_BYTES + 1) { 'a'.code.toByte() }
        val bomb = zip(
            listOf(
                "manifest.json" to "{}".toByteArray(),
                "settings.json" to oversized,
                "book-shield.yaml" to BookShieldYamlCodec.template().toByteArray(),
            ),
        )
        assertTrue(bomb.size < ConfigurationArchiveCodec.MAX_ARCHIVE_BYTES)
        assertThrows(IllegalStateException::class.java) {
            ConfigurationArchiveCodec.decode(bomb)
        }
    }

    private fun validFiles() = ConfigurationArchiveFiles(
        settingsJson = """{"format":"qdhelper-settings","version":1,"settings":{}}""",
        bookShieldYaml = BookShieldYamlCodec.template(),
    )

    private fun unzip(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                result[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        return result
    }

    private fun zip(entries: List<Pair<String, ByteArray>>): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        output.toByteArray()
    }

    private fun replaceAscii(source: ByteArray, before: String, after: String): ByteArray {
        require(before.length == after.length)
        val result = source.copyOf()
        val needle = before.toByteArray(Charsets.US_ASCII)
        val replacement = after.toByteArray(Charsets.US_ASCII)
        for (start in 0..result.size - needle.size) {
            if (needle.indices.all { offset -> result[start + offset] == needle[offset] }) {
                replacement.copyInto(result, start)
            }
        }
        return result
    }
}
