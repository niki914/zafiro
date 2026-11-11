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
}
