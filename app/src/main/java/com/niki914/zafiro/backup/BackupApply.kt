package com.niki914.zafiro.backup

import android.content.Context
import com.niki914.zafiro.api.util.runCatchingCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/** 一次导入对所选全组使用同一种策略。 */
enum class BackupStrategy {
    /** 本地没有的 id 新增；同 id 保留本地。 */
    Merge,

    /** 同 id 换成导入的。对话不删本地独有项，其余列表整份换成导入内容。 */
    Overwrite,
}

data class BackupApplyFailure(
    val partitionId: String,
    val message: String,
    val rolledBack: Boolean,
    val stackTrace: String = "",
)

data class BackupApplyReport(
    val counts: Map<String, PartitionChangeCounts>,
    val snapshotPath: String?,
    val failure: BackupApplyFailure?,
    val restartRequired: List<String>,
)

enum class ApplyRejectReason {
    NewerFormat,
    UnsafePath,
    NotAnArchive,
    MissingManifest,
    GroupNotInArchive,
}

sealed interface BackupApplyResult {
    data class Report(val report: BackupApplyReport) : BackupApplyResult
    data class Rejected(val reason: ApplyRejectReason) : BackupApplyResult

    /** 停在下一组之前，已经写上的按快照写回去了。 */
    data object Cancelled : BackupApplyResult
}

/**
 * 把 `.zafiro` 应用到存储。
 *
 * 第 3 步把系统文件选择器给的 [File] 或 [InputStream] 交到这里。
 * 决策在 [planPartition]，写入在 [BackupStore]。快照用导出同一套写入器，并且带上密钥。
 */
internal object BackupImporter {

    suspend fun apply(
        context: Context,
        source: File,
        selectedGroups: Set<BackupGroup>,
        strategy: BackupStrategy,
        isCancelled: () -> Boolean = { false },
    ): BackupApplyResult = withContext(Dispatchers.IO) {
        apply(
            source = source,
            selectedGroups = selectedGroups,
            strategy = strategy,
            store = LiveBackupStore(context),
            snapshotsDir = File(context.filesDir, SNAPSHOT_DIR),
            nowMillis = System.currentTimeMillis(),
            sync = { partitions ->
                if (BackupPartition.Preferences in partitions) syncPreferenceRuntime()
            },
            isCancelled = isCancelled,
        )
    }

    suspend fun apply(
        context: Context,
        source: InputStream,
        selectedGroups: Set<BackupGroup>,
        strategy: BackupStrategy,
    ): BackupApplyResult = withContext(Dispatchers.IO) {
        val store = LiveBackupStore(context)
        val file = materialize(source, store.scratchDir())
        try {
            apply(
                source = file,
                selectedGroups = selectedGroups,
                strategy = strategy,
                store = store,
                snapshotsDir = File(context.filesDir, SNAPSHOT_DIR),
                nowMillis = System.currentTimeMillis(),
                sync = { partitions ->
                    if (BackupPartition.Preferences in partitions) syncPreferenceRuntime()
                },
            )
        } finally {
            file.delete()
        }
    }

    suspend fun apply(
        source: InputStream,
        selectedGroups: Set<BackupGroup>,
        strategy: BackupStrategy,
        store: BackupStore,
        snapshotsDir: File,
        nowMillis: Long,
        sync: suspend (Set<BackupPartition>) -> Unit = {},
    ): BackupApplyResult {
        val file = materialize(source, store.scratchDir())
        return try {
            apply(file, selectedGroups, strategy, store, snapshotsDir, nowMillis, sync)
        } finally {
            file.delete()
        }
    }

    suspend fun apply(
        source: File,
        selectedGroups: Set<BackupGroup>,
        strategy: BackupStrategy,
        store: BackupStore,
        snapshotsDir: File,
        nowMillis: Long,
        sync: suspend (Set<BackupPartition>) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): BackupApplyResult {
        if (isCancelled()) return BackupApplyResult.Cancelled
        if (selectedGroups.isEmpty()) return BackupApplyResult.Report(emptyReport())
        val opened = openArchive(source)
        if (opened is OpenArchive.Bad) return BackupApplyResult.Rejected(opened.reason)
        val ready = opened as OpenArchive.Ready
        val present = ready.summary.partitions.values.mapNotNull { BackupGroup.fromId(it.groupId) }.toSet()
        if (!present.containsAll(selectedGroups)) {
            return BackupApplyResult.Rejected(ApplyRejectReason.GroupNotInArchive)
        }
        val partitions = BackupPartition.entries.filter { partition ->
            partition.group in selectedGroups && partition.id in ready.summary.partitions
        }
        if (isCancelled()) return BackupApplyResult.Cancelled
        val snapshot = runCatchingCancellable {
            writeSnapshot(store.read(), selectedGroups, snapshotsDir, nowMillis)
        }.getOrElse { throwable ->
            return BackupApplyResult.Report(
                emptyReport(failure = failureOf(SNAPSHOT_PARTITION, throwable, rolledBack = false)),
            )
        }
        if (isCancelled()) return BackupApplyResult.Cancelled
        return applyPartitions(ready.entries, partitions, strategy, store, snapshot, sync, isCancelled)
    }

    private suspend fun applyPartitions(
        entries: Map<String, ByteArray>,
        partitions: List<BackupPartition>,
        strategy: BackupStrategy,
        store: BackupStore,
        snapshot: File,
        sync: suspend (Set<BackupPartition>) -> Unit,
        isCancelled: () -> Boolean,
    ): BackupApplyResult {
        val applied = mutableListOf<BackupPartition>()
        val counts = linkedMapOf<String, PartitionChangeCounts>()
        var current = partitions.firstOrNull()?.id ?: SNAPSHOT_PARTITION
        val outcome = runCatchingCancellable {
            for (partition in partitions) {
                if (isCancelled()) return@applyPartitions onCancel(applied, counts, snapshot, store, current)
                current = partition.id
                val write = planPartition(
                    partition = partition,
                    entries = entries,
                    local = store.read(),
                    strategy = strategy,
                    restore = false,
                    imageDir = store.imageDir(),
                )
                applyWrite(store, write)
                counts[partition.id] = write.counts
                applied += partition
            }
            if (isCancelled()) return@applyPartitions onCancel(applied, counts, snapshot, store, current)
        }
        val thrown = outcome.exceptionOrNull()
        if (thrown != null) {
            val rollbackError = rollback(applied, snapshot, store)
            val rolledBack = rollbackError == null
            val committed = if (rolledBack) emptySet() else applied.toSet()
            return BackupApplyResult.Report(
                BackupApplyReport(
                    counts = counts,
                    snapshotPath = snapshot.absolutePath,
                    failure = failureOf(current, thrown, rolledBack),
                    restartRequired = restartRequired(committed),
                ),
            )
        }
        val restart = restartRequired(applied).toMutableList()
        val synced = runCatchingCancellable { sync(applied.toSet()) }
        if (synced.isFailure &&
            BackupPartition.Preferences in applied &&
            BackupPartition.Preferences.id !in restart
        ) {
            restart += BackupPartition.Preferences.id
        }
        return BackupApplyResult.Report(
            BackupApplyReport(
                counts = counts,
                snapshotPath = snapshot.absolutePath,
                failure = null,
                restartRequired = restart,
            ),
        )
    }

    private suspend fun onCancel(
        applied: List<BackupPartition>,
        counts: Map<String, PartitionChangeCounts>,
        snapshot: File,
        store: BackupStore,
        current: String,
    ): BackupApplyResult {
        val error = rollback(applied, snapshot, store)
        if (error == null) return BackupApplyResult.Cancelled
        return BackupApplyResult.Report(
            BackupApplyReport(
                counts = counts,
                snapshotPath = snapshot.absolutePath,
                failure = failureOf(current, error, rolledBack = false),
                restartRequired = restartRequired(applied),
            ),
        )
    }

    private suspend fun rollback(
        applied: List<BackupPartition>,
        snapshot: File,
        store: BackupStore,
    ): Throwable? {
        val opened = openArchive(snapshot)
        val ready = opened as? OpenArchive.Ready ?: return IllegalStateException("snapshot unreadable")
        return runCatchingCancellable {
            for (partition in applied.asReversed()) {
                val write = planPartition(
                    partition = partition,
                    entries = ready.entries,
                    local = store.read(),
                    strategy = BackupStrategy.Overwrite,
                    restore = true,
                    imageDir = store.imageDir(),
                )
                applyWrite(store, write)
            }
        }.exceptionOrNull()
    }
}

/** 对外 MCP 服务把配置缓存在内存里，写盘不会通知正在跑的服务。 */
internal fun restartRequired(partitions: Collection<BackupPartition>): List<String> {
    return partitions.mapNotNull { partition ->
        if (partition == BackupPartition.McpService) partition.id else null
    }
}

internal fun pruneSnapshots(dir: File, keep: Int = SNAPSHOTS_KEPT) {
    val files = dir.listFiles()
        ?.filter { it.isFile && it.name.startsWith(SNAPSHOT_PREFIX) && it.name.endsWith(SNAPSHOT_SUFFIX) }
        ?.sortedBy { it.name }
        ?: return
    val extra = files.size - keep
    if (extra <= 0) return
    files.take(extra).forEach { it.delete() }
}

private suspend fun applyWrite(store: BackupStore, write: PartitionWrite) {
    when (write) {
        is PartitionWrite.Document -> store.writeDocument(write.partition, write.json)
        is PartitionWrite.Conversations -> store.writeConversations(
            conversations = write.conversations,
            entries = write.entries,
            deleteIds = write.deleteIds,
            appStateJson = write.appStateJson,
            imageFiles = write.imageFiles,
        )
        is PartitionWrite.Skills -> store.writeSkills(
            statusJson = write.statusJson,
            skills = write.skills,
            deleteIds = write.deleteIds,
        )
    }
}

private fun writeSnapshot(
    snapshot: BackupSnapshot,
    groups: Set<BackupGroup>,
    dir: File,
    nowMillis: Long,
): File {
    val stamp = nowMillis.toString().padStart(15, '0')
    val unique = System.nanoTime().toString().padStart(18, '0')
    val file = File(dir, "$SNAPSHOT_PREFIX$stamp-$unique$SNAPSHOT_SUFFIX")
    val written = try {
        runCatchingCancellable {
            BackupExporter.export(
                BackupRequest(
                    groups = groups,
                    includeSecrets = true,
                    createdAtMillis = nowMillis,
                ),
                snapshot,
                file,
            )
            pruneSnapshots(dir)
        }
    } catch (cancelled: CancellationException) {
        file.delete()
        throw cancelled
    } catch (interrupted: InterruptedException) {
        file.delete()
        Thread.currentThread().interrupt()
        throw interrupted
    }
    if (written.isFailure) file.delete()
    written.getOrThrow()
    return file
}

private sealed interface OpenArchive {
    data class Ready(
        val entries: Map<String, ByteArray>,
        val summary: BackupSummary,
    ) : OpenArchive

    data class Bad(val reason: ApplyRejectReason) : OpenArchive
}

private fun openArchive(file: File): OpenArchive {
    return when (val zip = readZip(file)) {
        null -> OpenArchive.Bad(ApplyRejectReason.NotAnArchive)
        ZipRead.Unsafe -> OpenArchive.Bad(ApplyRejectReason.UnsafePath)
        is ZipRead.Ok -> when (val inspected = inspectEntries(zip.entries)) {
            is InspectResult.Rejected -> OpenArchive.Bad(inspected.reason.toApply())
            is InspectResult.Ok -> OpenArchive.Ready(zip.entries, inspected.summary)
        }
    }
}

private fun RejectReason.toApply(): ApplyRejectReason {
    return when (this) {
        RejectReason.NewerFormat -> ApplyRejectReason.NewerFormat
        RejectReason.UnsafePath -> ApplyRejectReason.UnsafePath
        RejectReason.NotAnArchive -> ApplyRejectReason.NotAnArchive
        RejectReason.MissingManifest -> ApplyRejectReason.MissingManifest
    }
}

private fun materialize(source: InputStream, scratch: File): File {
    scratch.mkdirs()
    val file = File(scratch, "incoming-${System.nanoTime()}.zafiro")
    file.outputStream().use { output -> source.copyTo(output) }
    return file
}

private fun emptyReport(failure: BackupApplyFailure? = null): BackupApplyReport {
    return BackupApplyReport(
        counts = emptyMap(),
        snapshotPath = null,
        failure = failure,
        restartRequired = emptyList(),
    )
}

private fun failureOf(
    partitionId: String,
    throwable: Throwable,
    rolledBack: Boolean,
): BackupApplyFailure {
    val message = throwable.message?.takeIf { it.isNotBlank() } ?: throwable.javaClass.simpleName
    return BackupApplyFailure(partitionId, message, rolledBack, throwable.stackTraceToString())
}

private const val SNAPSHOT_DIR = "backup/snapshots"
private const val SNAPSHOT_PREFIX = "snapshot-"
private const val SNAPSHOT_SUFFIX = ".zafiro"
private const val SNAPSHOTS_KEPT = 3
private const val SNAPSHOT_PARTITION = "snapshot"
