package com.tricreta.zafiro.autonomy.workspace

import org.json.JSONArray
import org.json.JSONObject

data class AgentDef(
    val id: String, val name: String, val instructions: String, val tools: List<String>, val skills: List<String>, val usesScreen: Boolean,
)

data class TaskDef(
    val key: String, val name: String, val prompt: String, val agent: String, val trigger: String, val time: String, val days: List<String>,
    val intervalMinutes: Long, val maxSteps: Int, val maxMinutes: Int, val criteria: String, val defaultEnabled: Boolean,
)

/** A switchable bundle for one platform: its team, schedule, skills, MCPs, rules and starting memory. Parsed from the assets/workspaces folder. */
data class WorkspaceDef(
    val id: String, val version: Int, val name: String, val platform: String, val app: String, val summary: String, val quotas: String,
    val agents: List<AgentDef>, val tasks: List<TaskDef>, val skills: List<String>, val mcps: List<String>, val memory: List<String>,
) {
    class Invalid(message: String) : IllegalArgumentException(message)

    companion object {
        private val TRIGGERS = setOf("NONE", "ONCE", "INTERVAL", "DAILY", "WEEKLY", "MONTHLY")

        fun parse(raw: String): WorkspaceDef {
            val o = try { JSONObject(raw) } catch (e: Exception) { throw Invalid("Not valid JSON: ${e.message}") }
            val id = o.optString("id").trim().ifEmpty { throw Invalid("Workspace needs an id.") }
            if (!Regex("[a-z0-9_-]{2,40}").matches(id)) throw Invalid("Workspace id \"$id\" must be lowercase letters, digits, - or _.")
            val agents = o.optJSONArray("agents").objs().map {
                AgentDef(
                    id = it.reqStr("id", id), name = it.reqStr("name", id), instructions = it.reqStr("instructions", id),
                    tools = it.optJSONArray("tools").strs(), skills = it.optJSONArray("skills").strs(), usesScreen = it.optBoolean("usesScreen", false),
                )
            }
            val agentIds = agents.map { it.id }.toSet()
            if (agentIds.size != agents.size) throw Invalid("Workspace $id has duplicate agent ids.")
            val tasks = o.optJSONArray("tasks").objs().map {
                val trigger = it.optString("trigger", "DAILY").uppercase()
                if (trigger !in TRIGGERS) throw Invalid("Workspace $id task \"${it.optString("key")}\": unknown trigger $trigger.")
                val time = it.optString("time", "")
                if ((trigger == "DAILY" || trigger == "WEEKLY" || trigger == "MONTHLY") && !Regex("([01]\\d|2[0-3]):[0-5]\\d").matches(time)) {
                    throw Invalid("Workspace $id task \"${it.optString("key")}\": time must be HH:mm.")
                }
                val agent = it.optString("agent", "")
                if (agent.isNotEmpty() && agent !in agentIds) throw Invalid("Workspace $id task \"${it.optString("key")}\" uses unknown agent \"$agent\".")
                TaskDef(
                    key = it.reqStr("key", id), name = it.reqStr("name", id), prompt = it.reqStr("prompt", id), agent = agent, trigger = trigger, time = time,
                    days = it.optJSONArray("days").strs().map { d -> d.uppercase().take(3) }, intervalMinutes = it.optLong("intervalMinutes", 0),
                    maxSteps = it.optInt("maxSteps", 80), maxMinutes = it.optInt("maxMinutes", 30), criteria = it.optString("criteria", ""),
                    defaultEnabled = it.optBoolean("enabled", true),
                )
            }
            if (tasks.map { it.key }.toSet().size != tasks.size) throw Invalid("Workspace $id has duplicate task keys.")
            return WorkspaceDef(
                id = id, version = o.optInt("version", 1), name = o.optString("name", id), platform = o.optString("platform", id), app = o.optString("app", ""),
                summary = o.optString("summary", ""), quotas = o.optString("quotas", ""), agents = agents, tasks = tasks,
                skills = o.optJSONArray("skills").strs(), mcps = o.optJSONArray("mcps").strs(), memory = o.optJSONArray("memory").strs(),
            )
        }

        private fun JSONObject.reqStr(k: String, ws: String): String = optString(k).trim().ifEmpty { throw Invalid("Workspace $ws: \"$k\" is required.") }
        private fun JSONArray?.strs(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it).trim() }.filter { it.isNotEmpty() }
        private fun JSONArray?.objs(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
    }
}
