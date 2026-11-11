package com.niki914.zafiro.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.niki914.logging.Logger
import com.niki914.zafiro.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Android glue for [BackupArchive]: what to include, where to read/write, and the restore staging. */
object BackupManager {
    private const val LOG_TAG = "niki914_zafiro_BackupManager"
    private val DATABASES = setOf("conversation_history.db", "autonomy.db")

    /** Caches that are re-fetched on demand; leaving them out keeps the backup small and avoids restoring stale facts. */
    private val EXCLUDED = setOf("settings/llm/model_facts.json", "settings/llm/openrouter_catalog.json")

    fun suggestedFileName(nowMs: Long = System.currentTimeMillis()): String =
        "zafiro-backup-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(nowMs)) + ".zip"

    private fun includesFile(relative: String): Boolean =
        relative !in EXCLUDED &&
            (relative.startsWith("settings/") || relative == "local_settings.json" || relative.startsWith("skills/"))

    private fun databasesDir(context: Context): File =
        context.getDatabasePath(DATABASES.first()).parentFile ?: context.filesDir

    suspend fun create(context: Context, destination: Uri): BackupArchive.Summary = withContext(Dispatchers.IO) {
        val dbDir = databasesDir(context)
        DATABASES.forEach { checkpoint(File(dbDir, it)) }
        val sources = listOf(
            BackupArchive.Source(BackupArchive.FILES_ROOT, context.filesDir, ::includesFile),
            BackupArchive.Source(BackupArchive.DB_ROOT, dbDir) { it in DATABASES },
        )
        val out = context.contentResolver.openOutputStream(destination, "wt")
            ?: error("Could not open the chosen file for writing.")
        out.use { BackupArchive.write(it, sources, BuildConfig.VERSION_NAME, System.currentTimeMillis()) }
            .also { Logger.i(LOG_TAG, "backup written files=${it.fileCount}") }
    }

    /** Validates and stages [source]; it is applied at the next app start (see [PendingRestore]). */
    suspend fun stageRestore(context: Context, source: Uri): BackupArchive.Summary = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(source) ?: error("Could not open the chosen file.")
        input.use { BackupArchive.extract(it, File(context.filesDir, PendingRestore.STAGING)) }
            .also { Logger.i(LOG_TAG, "restore staged files=${it.fileCount} backupVersion=${it.appVersion}") }
    }

    fun cancelStagedRestore(context: Context) {
        File(context.filesDir, PendingRestore.STAGING).deleteRecursively()
    }

    /** Called first thing in the main process, before any database or settings file is opened. */
    fun applyPendingRestore(context: Context) {
        val restored = PendingRestore.applyIfPending(context.filesDir, databasesDir(context), System.currentTimeMillis())
        if (restored > 0) Logger.i(LOG_TAG, "restore applied files=$restored (originals kept in restore_undo)")
    }

    /** Folds the write-ahead log into the main file so a plain file copy is a complete database. */
    private fun checkpoint(file: File) {
        if (!file.exists()) return
        runCatching {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            }
        }.onFailure { Logger.w(LOG_TAG, "checkpoint failed for ${file.name}: ${it.message}") }
    }
}
