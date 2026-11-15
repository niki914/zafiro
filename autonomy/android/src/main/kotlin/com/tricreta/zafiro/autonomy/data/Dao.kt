package com.tricreta.zafiro.autonomy.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AutonomyDao {

    // ---- tasks
    @Query("SELECT * FROM task ORDER BY name COLLATE NOCASE")
    fun observeTasks(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM task")
    suspend fun tasks(): List<TaskEntity>

    @Query("SELECT * FROM task WHERE id = :id")
    suspend fun task(id: String): TaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTask(task: TaskEntity)

    @Query("DELETE FROM task WHERE id = :id")
    suspend fun deleteTask(id: String)

    @Query("UPDATE task SET enabled = :enabled, updatedAtMs = :now WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean, now: Long)

    // ---- cursors
    @Query("SELECT * FROM cursor")
    suspend fun cursors(): List<CursorEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCursor(cursor: CursorEntity)

    @Query("DELETE FROM cursor WHERE taskId = :taskId")
    suspend fun deleteCursor(taskId: String)

    // ---- runs
    /** Returns -1 when (taskId, scheduledForMs) already exists — that is how double delivery is made harmless. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRun(run: RunEntity): Long

    @Query("SELECT DISTINCT taskId FROM run WHERE state IN ('QUEUED','STARTING','RUNNING','PAUSED')")
    suspend fun activeTaskIds(): List<String>

    @Query("SELECT COUNT(*) FROM run WHERE state = 'QUEUED'")
    suspend fun queuedCount(): Int

    @Query("SELECT * FROM run WHERE state = 'QUEUED' ORDER BY scheduledForMs LIMIT 1")
    suspend fun nextQueued(): RunEntity?

    @Query("SELECT * FROM run WHERE id = :id")
    suspend fun run(id: Long): RunEntity?

    @Query("SELECT * FROM run WHERE state IN ('STARTING','RUNNING','PAUSED')")
    suspend fun inFlight(): List<RunEntity>

    /** Compare-and-set: only one caller can move a run out of [from]. Returns rows changed (0 or 1). */
    @Query("UPDATE run SET state = :to, startedAtMs = COALESCE(startedAtMs, :now), heartbeatMs = :now WHERE id = :id AND state = :from")
    suspend fun transition(id: Long, from: String, to: String, now: Long): Int

    @Query(
        "UPDATE run SET state = :state, finishedAtMs = :now, summary = :summary, error = :error, " +
            "conversationId = COALESCE(:conversationId, conversationId), steps = :steps WHERE id = :id"
    )
    suspend fun finish(
        id: Long, state: String, now: Long, summary: String?, error: String?, conversationId: String?, steps: Int,
    )

    @Query("UPDATE run SET recoveries = :recoveries WHERE id = :id")
    suspend fun setRecoveries(id: Long, recoveries: Int)

    @Query("UPDATE run SET heartbeatMs = :now, steps = :steps WHERE id = :id")
    suspend fun heartbeat(id: Long, now: Long, steps: Int)

    @Query("SELECT * FROM run ORDER BY createdAtMs DESC, id DESC LIMIT :limit")
    fun observeRuns(limit: Int): Flow<List<RunEntity>>

    @Query("SELECT * FROM run WHERE taskId = :taskId ORDER BY createdAtMs DESC, id DESC LIMIT :limit")
    suspend fun runsForTask(taskId: String, limit: Int): List<RunEntity>

    // ---- events
    @Insert
    suspend fun insertEvent(event: RunEventEntity)

    @Query("SELECT * FROM run_event WHERE runId = :runId ORDER BY id")
    suspend fun events(runId: Long): List<RunEventEntity>

    @Query("DELETE FROM run_event WHERE runId = :runId")
    suspend fun deleteEvents(runId: Long)

    @Query("DELETE FROM run WHERE taskId = :taskId")
    suspend fun deleteRunsOfTask(taskId: String)

    /** Retention: keep history bounded on a 4 GB phone. */
    @Query("DELETE FROM run WHERE id IN (SELECT id FROM run WHERE state IN ('SUCCEEDED','FAILED','CANCELLED','LIMIT_REACHED','NEEDS_HUMAN','INTERRUPTED','MISSED') ORDER BY createdAtMs DESC LIMIT -1 OFFSET :keep)")
    suspend fun pruneRuns(keep: Int)

    @Query("DELETE FROM run_event WHERE runId NOT IN (SELECT id FROM run)")
    suspend fun pruneOrphanEvents()

    // --- agent profiles ---
    @Query("SELECT * FROM agent_profile ORDER BY name COLLATE NOCASE")
    fun observeAgents(): Flow<List<AgentProfileEntity>>

    @Query("SELECT * FROM agent_profile ORDER BY name COLLATE NOCASE")
    suspend fun agents(): List<AgentProfileEntity>

    @Query("SELECT * FROM agent_profile WHERE id = :id")
    suspend fun agent(id: String): AgentProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAgent(agent: AgentProfileEntity)

    @Query("DELETE FROM agent_profile WHERE id = :id")
    suspend fun deleteAgent(id: String)

    @Query("UPDATE task SET agentId = '' WHERE agentId = :id")
    suspend fun detachAgentFromTasks(id: String)

    // --- workspaces ---
    @Query("SELECT * FROM workspace")
    fun observeWorkspaces(): Flow<List<WorkspaceEntity>>

    @Query("SELECT * FROM workspace")
    suspend fun workspaces(): List<WorkspaceEntity>

    @Query("SELECT * FROM workspace WHERE id = :id")
    suspend fun workspace(id: String): WorkspaceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWorkspace(w: WorkspaceEntity)

    @Query("SELECT * FROM task WHERE workspaceId = :id")
    suspend fun tasksOfWorkspace(id: String): List<TaskEntity>

    @Query("UPDATE task SET enabled = :enabled, updatedAtMs = :now WHERE workspaceId = :id")
    suspend fun setWorkspaceTasksEnabled(id: String, enabled: Boolean, now: Long)

    @Query("UPDATE agent_profile SET enabled = :enabled, updatedAtMs = :now WHERE workspaceId = :id")
    suspend fun setWorkspaceAgentsEnabled(id: String, enabled: Boolean, now: Long)

    @Query("SELECT * FROM agent_profile WHERE workspaceId = :id")
    suspend fun agentsOfWorkspace(id: String): List<AgentProfileEntity>

    // --- board ---
    @Insert
    suspend fun insertBoardItem(item: BoardItemEntity): Long

    @Query("SELECT * FROM board_item WHERE id = :id")
    suspend fun boardItem(id: Long): BoardItemEntity?

    @Query("UPDATE board_item SET status = :status, result = :result, ownerAgent = CASE WHEN :owner = '' THEN ownerAgent ELSE :owner END, updatedAtMs = :now WHERE id = :id")
    suspend fun updateBoardItem(id: Long, status: String, result: String, owner: String, now: Long)

    @Query("SELECT * FROM board_item WHERE (:workspaceId = '' OR workspaceId = :workspaceId) ORDER BY updatedAtMs DESC, id DESC LIMIT :limit")
    suspend fun boardItems(workspaceId: String, limit: Int): List<BoardItemEntity>

    @Query("SELECT * FROM board_item ORDER BY updatedAtMs DESC, id DESC LIMIT :limit")
    fun observeBoard(limit: Int): Flow<List<BoardItemEntity>>

    @Query("SELECT * FROM board_item WHERE status IN ('TODO','DOING','BLOCKED') AND (:workspaceId = '' OR workspaceId = :workspaceId) ORDER BY id")
    suspend fun openBoardItems(workspaceId: String): List<BoardItemEntity>

    /** Work that was DOING when the process died: the next run resumes it instead of starting over. */
    @Query("UPDATE board_item SET status = 'TODO', updatedAtMs = :now WHERE status = 'DOING' AND updatedAtMs < :before")
    suspend fun reopenStaleBoardItems(before: Long, now: Long): Int

    @Query("DELETE FROM board_item WHERE status IN ('DONE','FAILED') AND updatedAtMs < :before")
    suspend fun pruneBoard(before: Long)

    // --- action log ---
    @Insert
    suspend fun insertAction(a: ActionLogEntity): Long

    @Query("SELECT * FROM action_log WHERE id = :id")
    suspend fun actionLog(id: Long): ActionLogEntity?

    @Query("UPDATE action_log SET status = :status, reason = :reason, updatedAtMs = :now WHERE id = :id")
    suspend fun setActionStatus(id: Long, status: String, reason: String, now: Long)

    @Query("SELECT COUNT(*) FROM action_log WHERE workspaceId = :workspaceId AND action = :action AND dayKey = :dayKey AND status IN ('PENDING','APPROVED','DONE')")
    suspend fun countActions(workspaceId: String, action: String, dayKey: String): Int

    @Query("SELECT * FROM action_log WHERE status = 'PENDING' ORDER BY id")
    fun observePendingActions(): Flow<List<ActionLogEntity>>

    @Query("SELECT * FROM action_log ORDER BY id DESC LIMIT :limit")
    fun observeActions(limit: Int): Flow<List<ActionLogEntity>>

    @Query("SELECT * FROM action_log WHERE (:workspaceId = '' OR workspaceId = :workspaceId) ORDER BY id DESC LIMIT :limit")
    suspend fun actions(workspaceId: String, limit: Int): List<ActionLogEntity>

    @Query("SELECT COUNT(*) FROM action_log WHERE workspaceId = :workspaceId AND dayKey = :dayKey AND status = 'DONE' AND action = :action")
    suspend fun doneToday(workspaceId: String, action: String, dayKey: String): Int

    // --- examples ---
    @Insert
    suspend fun insertExample(e: ExampleEntity): Long

    @Query("SELECT * FROM example WHERE (:platform = '' OR platform = :platform) ORDER BY id DESC LIMIT :limit")
    suspend fun examples(platform: String, limit: Int): List<ExampleEntity>

    @Query("DELETE FROM example WHERE id = :id")
    suspend fun deleteExample(id: Long)

    // --- tiers ---
    @Query("SELECT * FROM tier WHERE workspaceId = :workspaceId AND action = :action")
    suspend fun tier(workspaceId: String, action: String): TierEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTier(t: TierEntity)

    @Query("SELECT * FROM tier")
    fun observeTiers(): Flow<List<TierEntity>>

    // --- reset helpers ---
    @Query("DELETE FROM workspace WHERE id = :id")
    suspend fun deleteWorkspace(id: String)

    @Query("DELETE FROM board_item")
    suspend fun clearBoard()

    @Query("DELETE FROM action_log")
    suspend fun clearActions()

    @Query("DELETE FROM example")
    suspend fun clearExamples()

    @Query("DELETE FROM tier")
    suspend fun clearTiers()

    @Query("DELETE FROM run_event")
    suspend fun clearRunEvents()

    @Query("DELETE FROM run WHERE state NOT IN ('QUEUED','STARTING','RUNNING','PAUSED')")
    suspend fun clearFinishedRuns()

    @Query("SELECT * FROM run_event WHERE runId = :runId ORDER BY id")
    fun observeEvents(runId: Long): Flow<List<RunEventEntity>>

    @Query("SELECT dayKey AS day, COUNT(*) AS n FROM action_log WHERE status = 'DONE' GROUP BY dayKey ORDER BY dayKey DESC LIMIT :days")
    fun observeDoneByDay(days: Int): Flow<List<DayCount>>

    @Query("SELECT action AS label, COUNT(*) AS n FROM action_log WHERE status = 'DONE' AND dayKey = :dayKey GROUP BY action ORDER BY n DESC")
    fun observeDoneByKind(dayKey: String): Flow<List<KindCount>>
}

data class DayCount(val day: String, val n: Int)
data class KindCount(val label: String, val n: Int)
