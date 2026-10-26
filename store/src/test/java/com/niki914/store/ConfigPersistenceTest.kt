package com.niki914.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ConfigPersistenceTest {

    @get:Rule
    val silentLogger = SilentLoggerRule()

    private fun tempFile(name: String = "settings.json"): File {
        val dir = Files.createTempDirectory("config-persistence").toFile()
        return File(dir, name)
    }

    @Test
    fun readJsonFrom_missingFile_returnsNull() {
        assertNull(ConfigPersistence.readJsonFrom(tempFile(), "store"))
    }

    @Test
    fun writeThenRead_roundTrips() {
        val file = tempFile()

        ConfigPersistence.writeJsonTo(file, "store", """{"a":1}""")

        assertEquals("""{"a":1}""", ConfigPersistence.readJsonFrom(file, "store"))
    }

    @Test
    fun writeJsonTo_keepsSnapshotCopy() {
        val file = tempFile()

        ConfigPersistence.writeJsonTo(file, "store", """{"a":1}""")

        val snapshot = ConfigPersistence.snapshotFileFor(file)
        assertTrue(snapshot.exists())
        assertEquals("""{"a":1}""", snapshot.readText(Charsets.UTF_8))
    }

    @Test
    fun readJsonFrom_malformedMain_restoresFromSnapshot() {
        val file = tempFile()
        ConfigPersistence.writeJsonTo(file, "store", """{"a":1}""")
        file.writeText("""{"a":1, broken""", Charsets.UTF_8)

        val restored = ConfigPersistence.readJsonFrom(file, "store")

        assertEquals("""{"a":1}""", restored)
        // 主文件内容与快照一致
        assertEquals("""{"a":1}""", file.readText(Charsets.UTF_8))
    }

    @Test
    fun readJsonFrom_malformedMainWithoutSnapshot_returnsRawText() {
        val file = tempFile()
        file.writeText("not json at all", Charsets.UTF_8)

        val result = ConfigPersistence.readJsonFrom(file, "store")

        assertEquals("not json at all", result)
    }

    @Test
    fun writeJsonTo_malformedPayload_doesNotClobberSnapshot() {
        val file = tempFile()
        ConfigPersistence.writeJsonTo(file, "store", """{"a":1}""")

        ConfigPersistence.writeJsonTo(file, "store", """{"a":1""")

        assertEquals("""{"a":1}""", ConfigPersistence.snapshotFileFor(file).readText(Charsets.UTF_8))
        // 读取时自动回落到上一份可用内容
        assertEquals("""{"a":1}""", ConfigPersistence.readJsonFrom(file, "store"))
    }

    @Test
    fun isWellFormedJson_acceptsObjectsArraysAndScalars() {
        assertTrue(ConfigPersistence.isWellFormedJson("""{"a":1}"""))
        assertTrue(ConfigPersistence.isWellFormedJson("""[1,2,3]"""))
        assertTrue(ConfigPersistence.isWellFormedJson("\"text\""))
        assertTrue(ConfigPersistence.isWellFormedJson("42"))
    }

    @Test
    fun isWellFormedJson_rejectsBrokenAndBlank() {
        assertFalse(ConfigPersistence.isWellFormedJson(""))
        assertFalse(ConfigPersistence.isWellFormedJson("   "))
        assertFalse(ConfigPersistence.isWellFormedJson("""{"a":}"""))
        // JSONTokener 会把裸词当未加引号的字符串吞掉，语义校验是上层 codec 的职责；
        // 本层只防结构性损坏：不完整、或后面跟了垃圾
        assertFalse(ConfigPersistence.isWellFormedJson("""{"a":1"""))
        assertFalse(ConfigPersistence.isWellFormedJson("""{"a":1} trailing"""))
        assertFalse(ConfigPersistence.isWellFormedJson("""{"a":1} trailing"""))
    }

    @Test
    fun snapshotFileFor_appendsSuffixNextToMainFile() {
        val file = tempFile()

        assertEquals(file.path + ".good", ConfigPersistence.snapshotFileFor(file).path)
    }
}
