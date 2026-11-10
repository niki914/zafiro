package com.tricreta.zafiro.autonomy.data

import androidx.room.ColumnInfo
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
    /** Billable-token ceiling for one run (see TokenUsage.billable); -1 = unlimited. Added in schema v2. */
    @ColumnInfo(defaultValue = "-1") val maxTokens: Long = -1,
    /** Plain-language definition of "done". When set, the agent must verify it before the run counts as a success. */
    @ColumnInfo(defaultValue = "''") val successCriteria: String = "",
    /** Extra turns allowed after the first: failures retried, unmet criteria re-attempted. 0 = never recover. */
    @ColumnInfo(defaultValue = "2") val recoveries: Int = 2,
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
    /** How many recovery turns this run needed (failures continued, criteria re-attempted). Added in schema v2. */
    @ColumnInfo(defaultValue = "0") val recoveries: Int = 0,
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
