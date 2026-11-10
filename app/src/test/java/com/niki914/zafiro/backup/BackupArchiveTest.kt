package com.niki914.zafiro.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupArchiveTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun dir(name: String) = tmp.newFolder(name)

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, body) in entries) {
                z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray()); z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private val manifest = """{"format":1,"createdAtMs":5,"appVersion":"1.0","fileCount":1}"""

    @Test
    fun roundTripKeepsEveryFile() {
        val files = dir("files").also {
            File(it, "settings/llm").mkdirs()
            File(it, "settings/llm/saved_configs.json").writeText("""{"active_id":"x"}""")
            File(it, "skills/a").mkdirs(); File(it, "skills/a/SKILL.md").writeText("# a")
        }
        val dbs = dir("databases").also { File(it, "conversation_history.db").writeBytes(byteArrayOf(1, 2, 3)) }
        val out = ByteArrayOutputStream()
        val written = BackupArchive.write(
            out,
            listOf(BackupArchive.Source(BackupArchive.FILES_ROOT, files), BackupArchive.Source(BackupArchive.DB_ROOT, dbs)),
            appVersion = "1.2.3", nowMs = 1234,
        )
        assertEquals(3, written.fileCount)

        val staging = File(tmp.root, "stage")
        val summary = BackupArchive.extract(ByteArrayInputStream(out.toByteArray()), staging)
        assertEquals("1.2.3", summary.appVersion)
        assertEquals(1234L, summary.createdAtMs)
        assertEquals("""{"active_id":"x"}""", File(staging, "files/settings/llm/saved_configs.json").readText())
        assertEquals("# a", File(staging, "files/skills/a/SKILL.md").readText())
        assertEquals(listOf<Byte>(1, 2, 3), File(staging, "databases/conversation_history.db").readBytes().toList())
    }

    @Test
    fun excludeFilterSkipsCaches() {
        val files = dir("files2").also { File(it, "settings").mkdirs(); File(it, "settings/keep.json").writeText("1"); File(it, "settings/cache.json").writeText("2") }
        val out = ByteArrayOutputStream()
        val written = BackupArchive.write(out, listOf(BackupArchive.Source("files/", files) { !it.endsWith("cache.json") }), "v", 0)
        assertEquals(1, written.fileCount)
    }

    @Test
    fun pathTraversalIsRejectedAndNothingIsLeftBehind() {
        val bad = zipOf(MANIFEST to manifest, "files/../../evil.txt" to "x")
        val staging = File(tmp.root, "stage2")
        try {
            BackupArchive.extract(ByteArrayInputStream(bad), staging)
            fail("expected InvalidBackup")
        } catch (e: BackupArchive.InvalidBackup) {
            assertFalse(staging.exists())
            assertFalse(File(tmp.root, "evil.txt").exists())
        }
    }

    @Test
    fun unsafeNamesAreRefused() {
        for (name in listOf("/etc/passwd", "files/../x", "other/x", "files\\x", "", "files/./x", "databases/")) {
            assertNull(name, BackupArchive.safePath(name))
        }
        assertEquals("files/a/b.json", BackupArchive.safePath("files/a/b.json"))
    }

    @Test
    fun aZipWithoutManifestIsNotAZafiroBackup() {
        try {
            BackupArchive.extract(ByteArrayInputStream(zipOf("files/a" to "x")), File(tmp.root, "stage3"))
            fail("expected InvalidBackup")
        } catch (e: BackupArchive.InvalidBackup) {
            assertTrue(e.message!!.contains("manifest"))
        }
    }

    @Test
    fun garbageInputIsRejectedCleanly() {
        try {
            BackupArchive.extract(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)), File(tmp.root, "stage4"))
            fail("expected InvalidBackup")
        } catch (_: BackupArchive.InvalidBackup) {
        }
    }

    @Test
    fun futureFormatIsRefused() {
        val future = zipOf(MANIFEST to """{"format":99,"createdAtMs":1,"appVersion":"x","fileCount":0}""")
        try {
            BackupArchive.extract(ByteArrayInputStream(future), File(tmp.root, "stage5"))
            fail("expected InvalidBackup")
        } catch (e: BackupArchive.InvalidBackup) {
            assertTrue(e.message!!.contains("format"))
        }
    }

    @Test
    fun pendingRestoreReplacesFilesKeepsAnUndoCopyAndClearsStaging() {
        val filesDir = dir("appfiles"); val dbDir = dir("appdbs")
        File(filesDir, "settings").mkdirs(); File(filesDir, "settings/a.json").writeText("old")
        File(dbDir, "conversation_history.db").writeText("olddb")
        File(dbDir, "conversation_history.db-wal").writeText("wal")
        val staging = File(filesDir, PendingRestore.STAGING)
        File(staging, "files/settings").mkdirs(); File(staging, "files/settings/a.json").writeText("new")
        File(staging, "files/settings/b.json").writeText("added")
        File(staging, "databases").mkdirs(); File(staging, "databases/conversation_history.db").writeText("newdb")

        assertTrue(PendingRestore.isPending(filesDir))
        assertEquals(3, PendingRestore.applyIfPending(filesDir, dbDir, nowMs = 42))

        assertEquals("new", File(filesDir, "settings/a.json").readText())
        assertEquals("added", File(filesDir, "settings/b.json").readText())
        assertEquals("newdb", File(dbDir, "conversation_history.db").readText())
        assertFalse("stale WAL must not be replayed onto the restored db", File(dbDir, "conversation_history.db-wal").exists())
        assertEquals("old", File(filesDir, "restore_undo/42/files/settings/a.json").readText())
        assertEquals("olddb", File(filesDir, "restore_undo/42/databases/conversation_history.db").readText())
        assertFalse(PendingRestore.isPending(filesDir))
    }

    @Test
    fun nothingPendingMeansNothingChanges() {
        val filesDir = dir("f3"); val dbDir = dir("d3")
        assertEquals(0, PendingRestore.applyIfPending(filesDir, dbDir, 1))
    }

    private companion object {
        const val MANIFEST = "manifest.json"
    }
}
