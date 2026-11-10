package com.tricreta.zafiro.autonomy.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "task")
data class TaskEntity(
    @PrimaryKey val id: String,
    val name: String,
    val prompt: String,
    val enabled: Boolean,
    /** NONE (manual only) | ONCE | INTERVAL | DAILY | WEEKLY | MONTHLY */
    val triggerType: String,
    /** ISO local date-time, for ONCE. */
    val onceAt: String?,
    val intervalMinutes: Long?,
    val anchorMs: Long?,
    /** "HH:mm" for DAILY / WEEKLY / MONTHLY. */
    val timeOfDay: String?,
    /** Bit 0 = Monday … bit 6 = Sunday, for WEEKLY. */
    val daysMask: Int,
    /** 1..31, or -1 for the last day, for MONTHLY. */
    val dayOfMonth: Int,
    val zoneId: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    /** < 0 means Skip (only on-time runs); otherwise run a missed occurrence if at most this late. */
    val misfireGraceMs: Long,
    /** SKIP | QUEUE */
    val overlap: String,
    /** -1 = unlimited */
    val maxSteps: Int,
    /** -1 = unlimited */
    val maxDurationMs: Long,
    /** "" = use the globally active model configuration. */
    val configId: String,
    /** Unanswered approval requests are denied after this long. */
    val approvalTimeoutMs: Long,
)

@Entity(
    tableName = "run",
    indices = [Index(value = ["taskId", "scheduledForMs"], unique = true), Index("state")],
)
data class RunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: String,
    val taskName: String,
    val scheduledForMs: Long,
    /** [com.tricreta.zafiro.autonomy.core.RunState] name */
    val state: String,
    /** MissReason name for MISSED records */
    val reason: String? = null,
    val missedCount: Long = 0,
    val createdAtMs: Long,
    val startedAtMs: Long? = null,
    val finishedAtMs: Long? = null,
    val summary: String? = null,
    val error: String? = null,
    val conversationId: String? = null,
    val steps: Int = 0,
    val heartbeatMs: Long? = null,
    val manual: Boolean = false,
)

@Entity(tableName = "cursor")
data class CursorEntity(
    @PrimaryKey val taskId: String,
    val lastHandledMs: Long?,
)

@Entity(tableName = "run_event", indices = [Index("runId")])
data class RunEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: Long,
    val tsMs: Long,
    /** info | tool | approval | limit | error */
    val kind: String,
    val text: String,
)
