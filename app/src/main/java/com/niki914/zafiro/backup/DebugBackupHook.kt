package com.niki914.zafiro.backup

import android.content.Context
import com.niki914.logging.Logger
import com.niki914.zafiro.api.util.runCatchingCancellable
import com.niki914.zafiro.app.BuildConfig
import kotlinx.coroutines.delay
import java.io.File

/**
 * 临时调试入口。第 3 步删除。
 * 冷启动数秒后导出全部六组、不含密钥，写到应用私有目录 backup/zafiro-debug.zafiro。
 */
internal object DebugBackupHook {

    private const val LOG_TAG = "niki914_zafiro_DebugBackup"
    private const val DELAY_MS = 5_000L
    private const val DIR_NAME = "backup"
    private const val FILE_NAME = "zafiro-debug.zafiro"

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

    private fun describe(file: File, result: InspectResult.Ok): String {
        val parts = result.summary.partitions.entries.joinToString(",") { (id, summary) ->
            "$id:${summary.state.wire}:${summary.count}"
        }
        return "path=${file.absolutePath} bytes=${file.length()} " +
            "secrets=${result.summary.secrets.wire} " +
            "external=${result.summary.externalAttachmentPaths} partitions=$parts"
    }
}
