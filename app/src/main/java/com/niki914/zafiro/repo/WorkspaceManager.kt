package com.niki914.zafiro.repo

import android.content.Context
import com.niki914.logging.Logger
import com.tricreta.zafiro.autonomy.AutonomyGraph
import com.tricreta.zafiro.autonomy.workspace.WorkspaceDef
import com.tricreta.zafiro.autonomy.workspace.WorkspaceInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Bundled workspaces (assets/workspaces): one switchable team per platform. Switching one ON installs its agents and
 * tasks (first time), turns on its skills, seeds its screen routines and rulebook, and schedules its tasks. OFF pauses them.
 */
object WorkspaceManager {
    private const val TAG = "WorkspaceManager"
    @Volatile private var cache: List<WorkspaceDef>? = null

    private fun deletedFile(context: Context) = File(context.filesDir, "settings/workspaces_deleted.txt")

    fun deletedIds(context: Context): Set<String> = runCatching { deletedFile(context).readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet() }.getOrDefault(emptySet())

    /** Workspaces that ship with the app and are not deleted by the owner. */
    fun defs(context: Context): List<WorkspaceDef> = allDefs(context).filter { it.id !in deletedIds(context) }

    /** Removes a workspace for good (its agents, tasks and state), even a default one; "Restore deleted" brings it back. */
    suspend fun delete(context: Context, def: WorkspaceDef) = withContext(Dispatchers.IO) {
        val graph = AutonomyGraph.require(); val repo = graph.repo; val dao = repo.teamDao()
        dao.agentsOfWorkspace(def.id).forEach { repo.deleteAgent(it.id) }
        dao.tasksOfWorkspace(def.id).forEach { repo.deleteTask(it.id) }
        dao.deleteWorkspace(def.id)
        val f = deletedFile(context).also { it.parentFile?.mkdirs() }
        f.writeText((deletedIds(context) + def.id).joinToString("\n"))
        graph.scheduler.reconcile("workspace_deleted")
    }

    fun restoreDeleted(context: Context) { deletedFile(context).delete() }

    private fun allDefs(context: Context): List<WorkspaceDef> = cache ?: synchronized(this) {
        cache ?: (context.assets.list("workspaces").orEmpty().filter { it.endsWith(".json") }.sorted().mapNotNull { name ->
            runCatching { WorkspaceDef.parse(context.assets.open("workspaces/$name").bufferedReader().readText()) }
                .onFailure { Logger.w(TAG, "bad bundled workspace $name: ${it.message}") }.getOrNull()
        }).also { cache = it }
    }

    /** Copies bundled routines into files/routines. A file the owner edited (different from the last seeded copy) is never overwritten. */
    suspend fun seedRoutines(context: Context) = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "routines").also { it.mkdirs() }
        context.assets.list("routines").orEmpty().filter { it.endsWith(".json") }.forEach { name ->
            val bundled = context.assets.open("routines/$name").bufferedReader().readText()
            val target = File(dir, name)
            val seed = File(dir, ".$name.seed")
            val lastSeeded = seed.takeIf { it.isFile }?.readText()
            // New file, or untouched since last seeding -> take the bundled version.
            if (!target.isFile || (lastSeeded != null && target.readText() == lastSeeded)) {
                target.writeText(bundled); seed.writeText(bundled)
            }
        }
    }

    private fun ensureRulebook(context: Context, def: WorkspaceDef) {
        val f = File(context.filesDir, "outputs/${def.id}/RULES.md")
        if (f.isFile) return
        f.parentFile?.mkdirs()
        f.writeText(
            "# ${def.name} rulebook\n\nThis is the single source of rules for this workspace. Newest corrections are at the top; they override anything older.\n\n" +
                "- Never invent facts, results or experience. Say \"not available\" instead.\n" +
                "- Daily caps: ${def.quotas.replace(",", ", ")}. Stop at the cap.\n" +
                "- The owner switched this workspace on, so the team works alone. Only serious actions (direct messages, sending email, payments, deletions, applications) wait for approval; 10 approvals in a row make a type automatic, one rejection makes it ask again.\n" +
                "- Act like a person: pause between actions, vary what you do, stop on any warning or verification.\n" +
                "- Never bypass locks, CAPTCHAs, 2FA, consent screens or payments.\n",
        )
    }

    suspend fun setEnabled(context: Context, def: WorkspaceDef, enabled: Boolean): String = withContext(Dispatchers.IO) {
        val graph = AutonomyGraph.require()
        val installer = WorkspaceInstaller(graph.repo.teamDao())
        if (enabled) {
            seedRoutines(context)
            ensureRulebook(context, def)
            val r = installer.install(def)
            val scheduled = installer.setEnabled(def, true)
            def.skills.forEach { id -> runCatching { XRepo.skills.setEnabled(id, true) } }
            graph.scheduler.reconcile("workspace_on")
            buildString {
                append("${def.name} is ON: ${def.agents.size} agents, $scheduled scheduled tasks.")
                if (r.retiredLegacyTasks > 0) append(" ${r.retiredLegacyTasks} older hand-made task(s) for the same agents were switched off (kept, not deleted).")
            }
        } else {
            if (graph.repo.teamDao().workspace(def.id) != null) installer.setEnabled(def, false)
            graph.scheduler.reconcile("workspace_off")
            "${def.name} is OFF. Its tasks are paused."
        }
    }
}
