// 保护：工具截断输出目录（filesDir/tool_output）的多维容量与生命周期回收策略
package com.niki914.zafiro.util

import com.niki914.zafiro.chat.util.SilentLoggerRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ToolOutputRetentionTest {

    @get:Rule
    val silentLogger = SilentLoggerRule()

    private val baseMs = 1_700_000_000_000L

    private fun tempDir(): File = Files.createTempDirectory("tool-output-retention").toFile()

    private fun file(dir: File, name: String, bytes: Int, modifiedMs: Long): File {
        val file = File(dir, name)
        file.writeBytes(ByteArray(bytes))
        file.setLastModified(modifiedMs)
        return file
    }

    @Test
    fun prune_nullDir_isNoop() {
        val result = ToolOutputRetention.prune(null)

        assertEquals(0, result.deletedFiles)
        assertEquals(0L, result.freedBytes)
        assertFalse(result.changed)
    }

    @Test
    fun prune_missingDir_isNoop() {
        val result = ToolOutputRetention.prune(File(tempDir(), "absent"))

        assertEquals(0, result.deletedFiles)
    }

    @Test
    fun prune_emptyDir_isNoop() {
        val result = ToolOutputRetention.prune(tempDir())

        assertEquals(0, result.deletedFiles)
    }

    @Test
    fun prune_expiredFiles_deletedAndCounted() {
        val dir = tempDir()
        file(dir, "old-a.log", 100, baseMs)
        file(dir, "old-b.log", 50, baseMs)
        val fresh = file(dir, "fresh.log", 10, baseMs + 9 * 24 * 60 * 60 * 1000L)

        val result = ToolOutputRetention.prune(
            dir = dir,
            maxAgeMs = 7L * 24 * 60 * 60 * 1000,
            nowMs = baseMs + 10 * 24 * 60 * 60 * 1000L,
        )

        assertEquals(2, result.deletedFiles)
        assertEquals(150L, result.freedBytes)
        assertTrue(fresh.exists())
        assertEquals(1, dir.listFiles()?.size)
    }

    @Test
    fun prune_overFileLimit_keepsNewest() {
        val dir = tempDir()
        val oldest = file(dir, "a.log", 10, baseMs)
        val middle = file(dir, "b.log", 10, baseMs + 1_000)
        val newest = file(dir, "c.log", 10, baseMs + 2_000)

        val result = ToolOutputRetention.prune(
            dir = dir,
            maxFiles = 2,
            maxBytes = 0,
            maxAgeMs = 0,
            nowMs = baseMs + 3_000,
        )

        assertEquals(1, result.deletedFiles)
        assertFalse(oldest.exists())
        assertTrue(middle.exists())
        assertTrue(newest.exists())
    }

    @Test
    fun prune_overByteLimit_deletesOldestUntilUnderLimit() {
        val dir = tempDir()
        val oldest = file(dir, "a.log", 100, baseMs)
        val second = file(dir, "b.log", 100, baseMs + 1_000)
        val newest = file(dir, "c.log", 100, baseMs + 2_000)

        val result = ToolOutputRetention.prune(
            dir = dir,
            maxFiles = 0,
            maxBytes = 250,
            maxAgeMs = 0,
            nowMs = baseMs + 3_000,
        )

        assertEquals(1, result.deletedFiles)
        assertEquals(100L, result.freedBytes)
        assertFalse(oldest.exists())
        assertTrue(second.exists())
        assertTrue(newest.exists())
    }

    @Test
    fun prune_zeroLimits_deleteNothing() {
        val dir = tempDir()
        file(dir, "a.log", 4096, baseMs)

        val result = ToolOutputRetention.prune(
            dir = dir,
            maxFiles = 0,
            maxBytes = 0,
            maxAgeMs = 0,
            nowMs = baseMs + 365L * 24 * 60 * 60 * 1000,
        )

        assertEquals(0, result.deletedFiles)
        assertEquals(1, dir.listFiles()?.size)
    }

    @Test
    fun prune_subdirectories_areLeftAlone() {
        val dir = tempDir()
        val nested = File(dir, "nested").apply { mkdirs() }
        file(nested, "keep.log", 10, baseMs)

        ToolOutputRetention.prune(
            dir = dir,
            maxFiles = 1,
            maxBytes = 0,
            maxAgeMs = 0,
            nowMs = baseMs + 3_000,
        )

        assertTrue(nested.isDirectory)
        assertTrue(File(nested, "keep.log").exists())
    }
}
