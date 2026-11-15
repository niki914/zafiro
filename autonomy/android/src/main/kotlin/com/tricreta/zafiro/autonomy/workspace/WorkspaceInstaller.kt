package com.tricreta.zafiro.autonomy.workspace

import com.tricreta.zafiro.autonomy.data.AgentProfileEntity
import com.tricreta.zafiro.autonomy.data.AutonomyDao
import com.tricreta.zafiro.autonomy.data.CursorEntity
import com.tricreta.zafiro.autonomy.data.TaskEntity
import com.tricreta.zafiro.autonomy.data.TriggerTypes
import com.tricreta.zafiro.autonomy.data.WorkspaceEntity
import com.tricreta.zafiro.autonomy.data.daysToMask
import java.time.DayOfWeek

/**
 * Installs a [WorkspaceDef] into the database and switches it on or off.
 * - Install adds/updates agents and tasks (OFF until the owner switches the workspace on) only when the bundled version is newer
 *   than the installed one, so an app update can add improvements and an unchanged version never overwrites the owner's edits.
 * - Hand-made agents/tasks that the workspace replaces (same agent ids) are kept but switched off and tagged "legacy:<ws>".
 */
class WorkspaceInstaller(
    private val dao: AutonomyDao,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    data class Outcome(val installed: Boolean, val agents: Int, val tasks: Int, val retiredLegacyTasks: Int)

    suspend fun install(def: WorkspaceDef): Outcome {
        val existing = dao.workspace(def.id)
        if (existing != null && existing.installedVersion >= def.version) return Outcome(false, 0, 0, 0)
        val now = nowMs()
        val agentIds = def.agents.map { it.id }.toSet()

        // Adopt: tasks the owner (or an agent) created by hand for these agents are retired, not deleted.
        val legacy = dao.tasks().filter { it.workspaceId.isEmpty() && it.agentId in agentIds }
        legacy.forEach { dao.upsertTask(it.copy(enabled = false, workspaceId = "legacy:${def.id}", updatedAtMs = now)) }

        val wasOn = existing?.enabled ?: false
        def.agents.forEach { a ->
            val old = dao.agent(a.id)
            dao.upsertAgent(
                AgentProfileEntity(
                    id = a.id, name = a.name, instructions = a.instructions, configId = old?.configId.orEmpty(), tools = a.tools.joinToString(","),
                    skills = a.skills.joinToString(","), usesScreen = a.usesScreen, enabled = wasOn,
                    createdAtMs = old?.createdAtMs ?: now, updatedAtMs = now, workspaceId = def.id,
                ),
            )
        }
        // Tasks that were in an earlier version of this workspace but are gone now are removed.
        val keep = def.tasks.map { taskId(def.id, it.key) }.toSet()
        dao.tasksOfWorkspace(def.id).filter { it.id !in keep }.forEach { dao.deleteTask(it.id); dao.deleteCursor(it.id) }
        def.tasks.forEach { t ->
            val id = taskId(def.id, t.key)
            val old = dao.task(id)
            dao.upsertTask(toEntity(def, t, id, enabled = wasOn && t.defaultEnabled && (old?.enabled ?: true), now, old))
            dao.upsertCursor(CursorEntity(id, now))
        }
        dao.upsertWorkspace(WorkspaceEntity(def.id, wasOn, def.version, now, def.quotas))
        return Outcome(true, def.agents.size, def.tasks.size, legacy.size)
    }

    /** Switch the whole team and its schedule on or off. Returns the number of tasks that are now scheduled. */
    suspend fun setEnabled(def: WorkspaceDef, enabled: Boolean): Int {
        val now = nowMs()
        val ws = dao.workspace(def.id) ?: error("Workspace ${def.id} is not installed.")
        dao.upsertWorkspace(ws.copy(enabled = enabled, updatedAtMs = now))
        dao.setWorkspaceAgentsEnabled(def.id, enabled, now)
        var scheduled = 0
        def.tasks.forEach { t ->
            val id = taskId(def.id, t.key)
            val row = dao.task(id) ?: return@forEach
            val on = enabled && t.defaultEnabled
            dao.upsertTask(row.copy(enabled = on, updatedAtMs = now))
            if (on) { dao.upsertCursor(CursorEntity(id, now)); scheduled++ }
        }
        return scheduled
    }

    private fun toEntity(def: WorkspaceDef, t: TaskDef, id: String, enabled: Boolean, now: Long, old: TaskEntity?): TaskEntity {
        val days = t.days.mapNotNull { d -> DayOfWeek.entries.firstOrNull { it.name.startsWith(d) } }.toSet()
        return TaskEntity(
            id = id, name = t.name, prompt = t.prompt, enabled = enabled, triggerType = t.trigger, onceAt = null,
            intervalMinutes = t.intervalMinutes.takeIf { it > 0 && t.trigger == TriggerTypes.INTERVAL },
            anchorMs = if (t.trigger == TriggerTypes.INTERVAL) now else null,
            timeOfDay = t.time.takeIf { it.isNotEmpty() }, daysMask = if (days.isEmpty()) 0b1111111 else daysToMask(days), dayOfMonth = 1,
            zoneId = "", createdAtMs = old?.createdAtMs ?: now, updatedAtMs = now,
            misfireGraceMs = 6 * 3_600_000L, overlap = "SKIP", maxSteps = t.maxSteps, maxDurationMs = t.maxMinutes * 60_000L,
            configId = old?.configId.orEmpty(), approvalTimeoutMs = 120_000L, maxTokens = -1, successCriteria = t.criteria,
            recoveries = 2, agentId = t.agent, learn = true, workspaceId = def.id,
        )
    }

    companion object {
        fun taskId(ws: String, key: String) = "ws.$ws.$key"
    }
}
