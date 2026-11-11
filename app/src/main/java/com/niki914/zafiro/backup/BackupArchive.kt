package com.niki914.zafiro.backup

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * On-device backup format: one zip with a manifest and two roots, `files/` (= app filesDir: settings, API keys, MCP, Python
 * tools, memory, skills) and `databases/` (chat history, scheduled tasks and run history). Pure java.io so it is unit-tested.
 *
 * The archive holds API keys and MCP tokens in plain text, exactly as the app stores them today (ADR-0006 is not done), so
 * the file must be treated like a password file.
 */
object BackupArchive {
    const val FORMAT = 1
    const val MANIFEST = "manifest.json"
    const val FILES_ROOT = "files/"
    const val DB_ROOT = "databases/"
    private const val MAX_ENTRY_BYTES = 512L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 1024L * 1024 * 1024
    private const val MAX_ENTRIES = 20_000

    data class Source(val archiveRoot: String, val dir: File, val include: (relative: String) -> Boolean = { true })

    data class Summary(val format: Int, val createdAtMs: Long, val appVersion: String, val fileCount: Int)

    class InvalidBackup(message: String) : Exception(message)

    fun write(out: OutputStream, sources: List<Source>, appVersion: String, nowMs: Long): Summary {
        var count = 0
        ZipOutputStream(out.buffered()).use { zip ->
            for (source in sources) {
                if (!source.dir.exists()) continue
                source.dir.walkTopDown().filter { it.isFile }.forEach { file ->
                    val relative = file.relativeTo(source.dir).invariantSeparatorsPath
                    if (!source.include(relative)) return@forEach
                    zip.putNextEntry(ZipEntry(source.archiveRoot + relative))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    count++
                }
            }
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write("""{"format":$FORMAT,"createdAtMs":$nowMs,"appVersion":${quote(appVersion)},"fileCount":$count}""".toByteArray())
            zip.closeEntry()
        }
        return Summary(FORMAT, nowMs, appVersion, count)
    }

    /** Safe relative path for an entry, or null when it must be rejected (traversal, absolute, unknown root). */
    fun safePath(name: String): String? {
        if (name.isEmpty() || name.startsWith("/") || name.contains('\\') || name.contains('\u0000')) return null
        val parts = name.split('/')
        if (parts.any { it == ".." || it == "." }) return null
        if (!(name.startsWith(FILES_ROOT) || name.startsWith(DB_ROOT))) return null
        if (name.endsWith("/")) return null
        return name
    }

    /**
     * Validates [input] fully and extracts it into [stagingDir] (emptied first). Nothing outside [stagingDir] is touched,
     * and any problem aborts and deletes the partial staging, so a bad file can never half-apply.
     */
    fun extract(input: InputStream, stagingDir: File): Summary {
        stagingDir.deleteRecursively()
        stagingDir.mkdirs()
        val root = stagingDir.canonicalFile
        var manifest: Summary? = null
        var total = 0L
        var entries = 0
        try {
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    if (++entries > MAX_ENTRIES) throw InvalidBackup("too many files")
                    if (entry.name == MANIFEST) {
                        manifest = parseManifest(zip.readBytes().toString(Charsets.UTF_8))
                        continue
                    }
                    val safe = safePath(entry.name) ?: throw InvalidBackup("unsafe path in backup: ${entry.name.take(80)}")
                    val target = File(root, safe).canonicalFile
                    if (!target.path.startsWith(root.path + File.separator)) throw InvalidBackup("path escapes backup: $safe")
                    target.parentFile?.mkdirs()
                    var size = 0L
                    target.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = zip.read(buf)
                            if (n < 0) break
                            size += n
                            total += n
                            if (size > MAX_ENTRY_BYTES || total > MAX_TOTAL_BYTES) throw InvalidBackup("backup is too large")
                            o.write(buf, 0, n)
                        }
                    }
                }
            }
            val summary = manifest ?: throw InvalidBackup("this is not a Zafiro backup (no manifest)")
            if (summary.format != FORMAT) throw InvalidBackup("unsupported backup format ${summary.format}")
            return summary
        } catch (e: Exception) {
            stagingDir.deleteRecursively()
            throw if (e is InvalidBackup) e else InvalidBackup("could not read the backup: ${e.message}")
        }
    }

    private fun parseManifest(text: String): Summary {
        fun long(key: String) = Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull()
        val format = long("format")?.toInt() ?: throw InvalidBackup("manifest has no format")
        val version = Regex("\"appVersion\"\\s*:\\s*\"([^\"]*)\"").find(text)?.groupValues?.get(1).orEmpty()
        return Summary(format, long("createdAtMs") ?: 0L, version, (long("fileCount") ?: 0L).toInt())
    }

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

/**
 * A restore is staged, then applied at the NEXT app start before any database or settings file is opened (open files and
 * Room connections cannot be replaced safely while the app runs). Replaced originals are moved to `restore_undo/` first.
 */
object PendingRestore {
    const val STAGING = "pending_restore"
    const val UNDO = "restore_undo"

    fun isPending(filesDir: File): Boolean = File(filesDir, STAGING).isDirectory

    /** @return number of files restored; 0 when nothing was pending. Never throws: a failure leaves the app on its current data. */
    fun applyIfPending(filesDir: File, databasesDir: File, nowMs: Long): Int {
        val staging = File(filesDir, STAGING)
        if (!staging.isDirectory) return 0
        var restored = 0
        try {
            val undo = File(filesDir, "$UNDO/$nowMs").also { it.mkdirs() }
            fun place(sourceRoot: File, targetRoot: File, undoSub: String) {
                if (!sourceRoot.isDirectory) return
                sourceRoot.walkTopDown().filter { it.isFile }.forEach { file ->
                    val relative = file.relativeTo(sourceRoot).invariantSeparatorsPath
                    val target = File(targetRoot, relative)
                    if (target.exists()) {
                        val saved = File(undo, "$undoSub/$relative")
                        saved.parentFile?.mkdirs()
                        target.copyTo(saved, overwrite = true)
                    }
                    if (undoSub == "databases") {
                        File(target.path + "-wal").delete()
                        File(target.path + "-shm").delete()
                    }
                    target.parentFile?.mkdirs()
                    file.copyTo(target, overwrite = true)
                    restored++
                }
            }
            place(File(staging, BackupArchive.FILES_ROOT.trimEnd('/')), filesDir, "files")
            place(File(staging, BackupArchive.DB_ROOT.trimEnd('/')), databasesDir, "databases")
        } catch (_: Exception) {
            // keep going on current data; staging is removed below so we never loop on a broken restore
        } finally {
            staging.deleteRecursively()
        }
        return restored
    }
}
