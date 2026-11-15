package com.niki914.zafiro.repo

import android.content.Context
import com.niki914.store.StoreDescriptorRegistry
import com.tricreta.zafiro.autonomy.AutonomyGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/** What "reset to defaults" can remove. The owner ticks only what they want; model configurations are never touched. */
enum class ResetKind(val title: String, val summary: String) {
    TASKS("Custom tasks", "Scheduled tasks you or an agent created (workspace tasks are kept)"),
    AGENTS("Custom agents", "Agents you or an agent created (workspace agents are kept)"),
    WORKSPACES("Workspace teams", "Uninstalls every workspace's agents and tasks and switches them off; they come back when you switch one on again"),
    SKILLS("Custom skills", "Skills that did not come with the app"),
    MCP("Custom MCP servers", "MCP servers that did not come with the app, and saved sign-ins"),
    MEMORY_MAIN("Main memory", "Everything Zafiro remembered about you and your work"),
    MEMORY_AGENTS("Agent memories", "What each agent remembers"),
    TEAM_DATA("Team data", "Board, action log, approvals history, examples and run history"),
    FILES("Saved files", "Everything in the Files page"),
}

object ResetManager {
    private val BUNDLED_MCP = setOf(
        "DeepWiki", "Context7", "Microsoft Learn", "Cloudflare Docs", "Notion", "Linear", "GitHub", "Sentry", "Asana", "Atlassian", "Canva",
        "Figma", "Stripe", "PayPal", "Square", "Vercel", "Supabase", "Hugging Face", "Exa Search",
    )

    suspend fun reset(context: Context, kinds: Set<ResetKind>): String = withContext(Dispatchers.IO) {
        val graph = AutonomyGraph.require()
        val repo = graph.repo
        val dao = repo.teamDao()
        val done = mutableListOf<String>()
        if (ResetKind.WORKSPACES in kinds) {
            dao.workspaces().forEach { w ->
                dao.agentsOfWorkspace(w.id).forEach { repo.deleteAgent(it.id) }
                dao.tasksOfWorkspace(w.id).forEach { repo.deleteTask(it.id) }
                dao.deleteWorkspace(w.id)
            }
            done += "workspaces"
        }
        if (ResetKind.TASKS in kinds) {
            repo.tasks().filter { it.workspaceId.isEmpty() || it.workspaceId.startsWith("legacy:") }.forEach { repo.deleteTask(it.id) }
            done += "tasks"
        }
        if (ResetKind.AGENTS in kinds) {
            repo.agents().filter { it.workspaceId.isEmpty() }.forEach { repo.deleteAgent(it.id) }
            done += "agents"
        }
        if (ResetKind.TEAM_DATA in kinds) {
            dao.clearBoard(); dao.clearActions(); dao.clearExamples(); dao.clearTiers(); dao.clearRunEvents(); dao.clearFinishedRuns()
            done += "team data"
        }
        if (ResetKind.SKILLS in kinds) {
            val bundled = context.assets.list("skills").orEmpty().toSet()
            XRepo.skills.listAll().filter { it.id !in bundled }.forEach { runCatching { XRepo.skills.delete(it.id) } }
            done += "skills"
        }
        if (ResetKind.MCP in kinds) {
            XRepo.updateJsonOrFalse(StoreDescriptorRegistry.TOOLS_MCP_SERVERS_ID) { json ->
                val servers = McpSettingsCodec.parseServers(json)
                McpSettingsCodec.encodeServers(servers.filter { it.name in BUNDLED_MCP })
            }
            File(context.filesDir, "settings/mcp_oauth.json").delete()
            done += "MCP servers"
        }
        if (ResetKind.MEMORY_MAIN in kinds) { XRepo.memory.replaceAll(emptyList()); done += "main memory" }
        if (ResetKind.MEMORY_AGENTS in kinds) {
            File(context.filesDir, "settings/agents").listFiles()?.forEach { dir ->
                val f = File(dir, "memory.json")
                if (dir.name != "main" && f.isFile) f.writeText("""{"memories":[]}""")
            }
            done += "agent memories"
        }
        if (ResetKind.FILES in kinds) { File(context.filesDir, "outputs").deleteRecursively(); done += "files" }
        graph.scheduler.reconcile("reset")
        if (done.isEmpty()) "Nothing selected." else "Removed: ${done.joinToString(", ")}."
    }

    // ---- memories of agents other than main, as plain readable entries ----
    data class AgentMemory(val agent: String, val index: Int, val text: String)

    suspend fun agentMemories(context: Context): List<AgentMemory> = withContext(Dispatchers.IO) {
        File(context.filesDir, "settings/agents").listFiles().orEmpty().filter { it.isDirectory && it.name != "main" }.sortedBy { it.name }.flatMap { dir ->
            val f = File(dir, "memory.json")
            val arr = runCatching { (Json.parseToJsonElement(f.readText()) as JsonObject)["memories"] as? JsonArray }.getOrNull()
            arr.orEmpty().mapIndexedNotNull { i, e ->
                ((e as? JsonObject)?.get("content") as? JsonPrimitive)?.content?.let { AgentMemory(dir.name, i, it) }
            }
        }
    }

    suspend fun deleteAgentMemory(context: Context, agent: String, index: Int) = withContext(Dispatchers.IO) {
        val f = File(context.filesDir, "settings/agents/$agent/memory.json")
        val obj = runCatching { Json.parseToJsonElement(f.readText()) as JsonObject }.getOrNull() ?: return@withContext
        val arr = (obj["memories"] as? JsonArray) ?: return@withContext
        f.writeText(JsonObject(obj + ("memories" to JsonArray(arr.filterIndexed { i, _ -> i != index }))).toString())
    }
}
