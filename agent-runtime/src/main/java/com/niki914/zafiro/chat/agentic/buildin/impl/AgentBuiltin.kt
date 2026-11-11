package com.niki914.zafiro.chat.agentic.buildin.impl

import com.niki914.zafiro.api.AgentDelegator
import com.niki914.zafiro.api.DelegateResult
import com.niki914.zafiro.chat.agentic.buildin.BuiltinTool
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolResult
import com.niki914.zafiro.service.findService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Multi-agent control. `list` shows the owner's agents; `call` hands one a task (foreground = wait for the answer,
 * background = keep working while you continue); `result` collects a background answer. Agents run in their own
 * conversations, so several can work at once; only one that uses the screen runs at a time.
 */
class AgentBuiltin : BuiltinTool() {
    override val name: String = "agent"
    override val description: String =
        "Work with other Zafiro agents. action=list shows available agents. action=call (agent, task, background) gives an agent a task: " +
            "background=false waits for its answer, background=true returns a handle immediately so you can keep working or call more agents in parallel. " +
            "action=result (handle, wait_seconds) collects a background agent's answer. Give each agent a complete, self-contained task."
    override val defaultEnabled: Boolean = true
    override val inputSchemaJson: String =
        """{"type":"object","properties":{"action":{"type":"string","enum":["list","call","result"]},"agent":{"type":"string"},"task":{"type":"string"},"background":{"type":"boolean"},"handle":{"type":"string"},"wait_seconds":{"type":"number"}},"required":["action"]}"""

    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
        val args = runCatching { Json.parseToJsonElement(request.argumentsJson) as? JsonObject }.getOrNull()
            ?: return invalid("Arguments must be a JSON object.")
        val delegator = findService<AgentDelegator>()
            ?: return BuiltinToolResult.failure(code = "AGENTS_UNAVAILABLE", message = "The agent system is not running yet.")
        fun str(k: String) = args[k]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        return when (str("action").lowercase()) {
            "list" -> {
                val agents = delegator.list()
                BuiltinToolResult.success(
                    message = if (agents.isEmpty()) "No agents are defined yet." else "${agents.size} agent(s).",
                    data = buildJsonObject {
                        put("agents", buildJsonArray {
                            agents.forEach { add(buildJsonObject { put("name", it.name); put("summary", it.summary); put("usesScreen", it.usesScreen) }) }
                        })
                    },
                )
            }
            "call" -> {
                if (str("agent").isEmpty() || str("task").isEmpty()) return invalid("call needs 'agent' and 'task'.")
                val background = args["background"]?.jsonPrimitive?.booleanOrNull ?: false
                toResult(delegator.call(str("agent"), str("task"), background))
            }
            "result" -> {
                if (str("handle").isEmpty()) return invalid("result needs a 'handle'.")
                val wait = ((args["wait_seconds"]?.jsonPrimitive?.doubleOrNull ?: 0.0).coerceIn(0.0, 120.0) * 1000).toLong()
                toResult(delegator.result(str("handle"), wait))
            }
            else -> invalid("'action' must be list, call or result.")
        }
    }

    private fun toResult(r: DelegateResult): BuiltinToolResult {
        val data = buildJsonObject {
            put("status", r.status)
            put("text", r.text)
            r.handle?.let { put("handle", it) }
        }
        return when (r.status) {
            "DONE", "RUNNING" -> BuiltinToolResult.success(message = "${r.status}: ${r.text.take(200)}", data = data)
            else -> BuiltinToolResult.failure(code = r.status, message = r.text.take(500), data = data)
        }
    }

    private fun invalid(message: String) = BuiltinToolResult.failure(
        code = "INVALID_ARGUMENTS", message = message,
        hint = """Example: {"action":"call","agent":"Researcher","task":"Find 5 competitors","background":true}""",
    )
}
