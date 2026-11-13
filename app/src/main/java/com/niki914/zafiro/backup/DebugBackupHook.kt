package com.niki914.zafiro.backup

import android.content.Context
import com.niki914.logging.Logger
import com.niki914.zafiro.api.util.runCatchingCancellable
import com.niki914.zafiro.app.BuildConfig
import kotlinx.coroutines.delay
import java.io.File

/**
 * 临时调试入口。第 3 步删除。
 * 冷启动时先处理 backup/import.zafiro：检视后按合并导入包里已有的全部组，再改名。
 * 这一步结束之后，才延迟数秒导出全部组、不含密钥，写到 backup/zafiro-debug.zafiro。
 */
internal object DebugBackupHook {

    private const val LOG_TAG = "niki914_zafiro_DebugBackup"
    private const val DELAY_MS = 5_000L
    private const val DIR_NAME = "backup"
    private const val FILE_NAME = "zafiro-debug.zafiro"
    private const val IMPORT_NAME = "import.zafiro"

    /**
     * 临时调试入口。第 3 步删除。
     * 必须在 [exportOnce] 之前跑完：同一次冷启动里先导入，再导出。
     */
    suspend fun importOnce(context: Context) {
        val file = File(File(context.filesDir, DIR_NAME), IMPORT_NAME)
        if (!file.isFile) return
        val outcome = runCatchingCancellable { importExisting(context, file) }
        val error = outcome.exceptionOrNull() ?: return
        if (file.isFile) rename(file, failedName())
        Logger.e(LOG_TAG, "debug import failed path=${file.absolutePath}", error)
    }

    suspend fun exportOnce(context: Context) {
        delay(DELAY_MS)
        val outcome = runCatchingCancellable { write(context) }
        outcome.exceptionOrNull()?.let { error ->
            Logger.e(LOG_TAG, "debug backup failed", error)
        }
    }

    private suspend fun write(context: Context) {
        val request = BackupRequest(
            groups = BackupGroup.entries.toSet(),
            includeSecrets = false,
            createdAtMillis = System.currentTimeMillis(),
            appVersion = BuildConfig.VERSION_NAME,
        )
        val file = File(File(context.filesDir, DIR_NAME), FILE_NAME)
        BackupExporter.export(request, BackupLiveSource.read(), file)
        when (val inspected = BackupExporter.inspect(file)) {
            is InspectResult.Ok -> Logger.i(LOG_TAG, describe(file, inspected))
            is InspectResult.Rejected -> Logger.e(
                LOG_TAG,
                "debug backup rejected reason=${inspected.reason} path=${file.absolutePath} bytes=${file.length()}",
            )
        }
    }

    private suspend fun importExisting(context: Context, file: File) {
        when (val inspected = BackupExporter.inspect(file)) {
            is InspectResult.Rejected -> {
                rename(file, failedName())
                Logger.e(
                    LOG_TAG,
                    "debug import rejected reason=${inspected.reason} path=${file.absolutePath}",
                )
            }
            is InspectResult.Ok -> {
                val groups = inspected.summary.partitions.values
                    .mapNotNull { BackupGroup.fromId(it.groupId) }
                    .toSet()
                when (val result = BackupImporter.apply(context, file, groups, BackupStrategy.Merge)) {
                    is BackupApplyResult.Rejected -> {
                        rename(file, failedName())
                        Logger.e(LOG_TAG, "debug import rejected reason=${result.reason} path=${file.absolutePath}")
                    }
                    is BackupApplyResult.Report -> {
                        rename(file, if (result.report.failure == null) doneName() else failedName())
                        Logger.i(LOG_TAG, describeImport(result.report))
                    }
                }
            }
        }
    }

    private fun describeImport(report: BackupApplyReport): String {
        val counts = report.counts.entries.joinToString(",") { (id, count) ->
            "$id:added=${count.added}:replaced=${count.replaced}:" +
                "conflictSkipped=${count.conflictSkipped}:untouched=${count.untouched}"
        }
        val failure = report.failure?.let { item ->
            "partition=${item.partitionId} rolledBack=${item.rolledBack} message=${item.message}"
        } ?: "none"
        val snapshot = report.snapshotPath ?: "none"
        return "counts=$counts snapshot=$snapshot failure=$failure restart=${report.restartRequired}"
    }

    private fun rename(file: File, name: String) {
        val target = File(file.parentFile, name)
        if (!file.renameTo(target)) {
            Logger.e(LOG_TAG, "debug import rename failed path=${file.absolutePath} target=${target.absolutePath}")
        }
    }

    private fun doneName(): String = "$IMPORT_NAME.done-${System.currentTimeMillis()}"

    private fun failedName(): String = "$IMPORT_NAME.failed-${System.currentTimeMillis()}"

    private fun describe(file: File, result: InspectResult.Ok): String {
        val parts = result.summary.partitions.entries.joinToString(",") { (id, summary) ->
            "$id:${summary.state.wire}:${summary.count}"
        }
        return "path=${file.absolutePath} bytes=${file.length()} " +
            "secrets=${result.summary.secrets.wire} " +
            "external=${result.summary.externalAttachmentPaths} partitions=$parts"
    }
}
