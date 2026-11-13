package com.niki914.zafiro.app.ui.model.backup

import com.niki914.zafiro.api.util.runCatchingCancellable
import com.niki914.zafiro.app.BuildConfig
import com.niki914.zafiro.app.StartupWrites
import com.niki914.zafiro.backup.BackupApplyFailure
import com.niki914.zafiro.backup.BackupApplyResult
import com.niki914.zafiro.backup.BackupExporter
import com.niki914.zafiro.backup.BackupGroup
import com.niki914.zafiro.backup.BackupImporter
import com.niki914.zafiro.backup.BackupLiveSource
import com.niki914.zafiro.backup.BackupRequest
import com.niki914.zafiro.backup.BackupStrategy
import com.niki914.zafiro.backup.InspectResult
import com.niki914.zafiro.repo.XRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal interface BackupSettingsWork {
    suspend fun prepareExport(
        groups: Set<BackupGroup>,
        includeSecrets: Boolean,
        isCancelled: () -> Boolean,
    ): ExportPrep

    suspend fun inspect(file: File): InspectLook

    suspend fun apply(
        file: File,
        groups: Set<BackupGroup>,
        strategy: BackupStrategy,
        isCancelled: () -> Boolean,
    ): ImportOutcome
}

internal sealed interface ExportPrep {
    data class Ready(val path: String) : ExportPrep
    data object Cancelled : ExportPrep
    data class Failed(val stackTrace: String) : ExportPrep
}

internal sealed interface InspectLook {
    data class Ok(val groups: List<BackupGroup>) : InspectLook
    data object Bad : InspectLook
}

internal sealed interface ImportOutcome {
    data object Success : ImportOutcome
    data object Cancelled : ImportOutcome
    data class Failed(val dataUnmodified: Boolean, val stackTrace: String) : ImportOutcome
}

internal object LiveBackupSettingsWork : BackupSettingsWork {
    override suspend fun prepareExport(
        groups: Set<BackupGroup>,
        includeSecrets: Boolean,
        isCancelled: () -> Boolean,
    ): ExportPrep = withContext(Dispatchers.IO) {
        if (isCancelled()) return@withContext ExportPrep.Cancelled
        val snapshot = BackupLiveSource.read()
        if (isCancelled()) return@withContext ExportPrep.Cancelled
        val context = XRepo.context()
        val file = File(context.cacheDir, "backup-export-${System.nanoTime()}.zafiro")
        val written = runCatchingCancellable {
            BackupExporter.export(
                BackupRequest(
                    groups = groups,
                    includeSecrets = includeSecrets,
                    createdAtMillis = System.currentTimeMillis(),
                    appVersion = BuildConfig.VERSION_NAME,
                ),
                snapshot,
                file,
            )
        }
        val error = written.exceptionOrNull()
        if (error != null) {
            file.delete()
            return@withContext ExportPrep.Failed(error.stackTraceToString())
        }
        if (isCancelled()) {
            file.delete()
            return@withContext ExportPrep.Cancelled
        }
        ExportPrep.Ready(file.absolutePath)
    }

    override suspend fun inspect(file: File): InspectLook = withContext(Dispatchers.IO) {
        when (val result = BackupExporter.inspect(file)) {
            is InspectResult.Rejected -> InspectLook.Bad
            is InspectResult.Ok -> {
                val present = result.summary.partitions.values.map { it.groupId }.toSet()
                val groups = backupSheetOptions.filter { it.id in present }
                if (groups.isEmpty()) InspectLook.Bad else InspectLook.Ok(groups)
            }
        }
    }

    override suspend fun apply(
        file: File,
        groups: Set<BackupGroup>,
        strategy: BackupStrategy,
        isCancelled: () -> Boolean,
    ): ImportOutcome {
        StartupWrites.await()
        if (isCancelled()) return ImportOutcome.Cancelled
        val context = XRepo.context()
        val result = BackupImporter.apply(
            context = context,
            source = file,
            selectedGroups = groups,
            strategy = strategy,
            isCancelled = isCancelled,
        )
        return result.toImportOutcome()
    }
}

private fun BackupApplyResult.toImportOutcome(): ImportOutcome = when (this) {
    BackupApplyResult.Cancelled -> ImportOutcome.Cancelled
    is BackupApplyResult.Rejected -> ImportOutcome.Failed(
        dataUnmodified = true,
        stackTrace = reason.name,
    )
    is BackupApplyResult.Report -> {
        val failure = report.failure
        if (failure == null) {
            ImportOutcome.Success
        } else {
            ImportOutcome.Failed(
                dataUnmodified = failure.dataUnmodified(),
                stackTrace = failure.stackTrace.ifBlank { failure.message },
            )
        }
    }
}

private fun BackupApplyFailure.dataUnmodified(): Boolean {
    return rolledBack || partitionId == "snapshot"
}
