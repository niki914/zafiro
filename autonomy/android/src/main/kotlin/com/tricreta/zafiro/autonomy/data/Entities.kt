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
    /** Agent profile that performs this task; blank = a plain run. Added in schema v3. */
    @ColumnInfo(defaultValue = "''") val agentId: String = "",
    /** After the run, the agent saves lessons to memory. Added in schema v3. */
    @ColumnInfo(defaultValue = "1") val learn: Boolean = true,
    /** Workspace that installed this task ("" = created by hand). Added in schema v4. */
    @ColumnInfo(defaultValue = "''") val workspaceId: String = "",
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

/** A reusable agent: instructions + model + guidance on tools/skills. Added in schema v3. */
@Entity(tableName = "agent_profile")
data class AgentProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val instructions: String,
    /** Saved model config id; blank = the active one. */
    val configId: String,
    /** Comma-separated tool names the agent should stick to; blank = no restriction (guidance, not a sandbox). */
    val tools: String,
    /** Comma-separated skill ids the agent loads first. */
    val skills: String,
    /** Only one agent that uses the screen runs at a time; others run in parallel. */
    val usesScreen: Boolean,
    val enabled: Boolean,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    /** Workspace that installed this agent ("" = created by hand). Added in schema v4. */
    @ColumnInfo(defaultValue = "''") val workspaceId: String = "",
)

/** A switchable bundle (agents + tasks + skills + MCPs + memory) for one platform. Content lives in assets; this row is its state. */
@Entity(tableName = "workspace")
data class WorkspaceEntity(
    @PrimaryKey val id: String,
    val enabled: Boolean,
    /** Version of the bundled definition last installed, so an app update can add new defaults without overwriting edits. */
    val installedVersion: Int,
    val updatedAtMs: Long,
    /** Daily caps, "comment=15,like=30". Blank = defaults. */
    @ColumnInfo(defaultValue = "''") val quotas: String = "",
)

/** One unit of work handed between agents. The board is the shared truth: owner, status, result. */
@Entity(tableName = "board_item", indices = [Index("workspaceId"), Index("status"), Index("parentId")])
data class BoardItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workspaceId: String,
    val parentId: Long?,
    val title: String,
    val detail: String,
    val ownerAgent: String,
    val createdBy: String,
    /** TODO | DOING | DONE | FAILED | BLOCKED */
    val status: String,
    val result: String,
    val depth: Int,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

/** Every outward action (post, comment, reply, like...), for daily caps, approvals, tiering and the dashboard. */
@Entity(tableName = "action_log", indices = [Index("dayKey"), Index("workspaceId")])
data class ActionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workspaceId: String,
    val platform: String,
    /** post | note | comment | reply | like | restack | follow | dm | other */
    val action: String,
    val target: String,
    val text: String,
    /** yyyy-MM-dd in the owner's zone. */
    val dayKey: String,
    /** PENDING | APPROVED | DONE | REJECTED | FAILED */
    val status: String,
    val agent: String,
    val reason: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

/** Owner-judged samples the Reviewer compares new drafts with. */
@Entity(tableName = "example", indices = [Index("platform")])
data class ExampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val platform: String,
    val kind: String,
    val text: String,
    /** GOOD | BAD */
    val verdict: String,
    val reason: String,
    val createdAtMs: Long,
)

/** Approval streak per (workspace, action type) for tiered autonomy. */
@Entity(tableName = "tier", primaryKeys = ["workspaceId", "action"])
data class TierEntity(
    val workspaceId: String,
    val action: String,
    val streak: Int,
    val auto: Boolean,
)
