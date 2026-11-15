package com.niki914.zafiro.chat.agentic.buildin.impl

import com.niki914.zafiro.api.TeamService
import com.niki914.zafiro.chat.agentic.buildin.BuiltinTool
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolResult
import com.niki914.zafiro.service.findService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Forwards a call to the [TeamService]; the three tools differ only in name, help text and schema. */
abstract class TeamForwardBuiltin(
    override val name: String,
    override val description: String,
    override val inputSchemaJson: String,
) : BuiltinTool() {
    override val defaultEnabled: Boolean = true

    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
        val obj = runCatching { Json.parseToJsonElement(request.argumentsJson) as? JsonObject }.getOrNull()
            ?: return BuiltinToolResult.failure(code = "INVALID_ARGUMENTS", message = "Arguments must be a JSON object.")
        val service = findService<TeamService>()
            ?: return BuiltinToolResult.failure(code = "TEAM_UNAVAILABLE", message = "The team system is not running yet.")
        val args = obj.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull ?: v.toString() }
        val r = service.run(name, args)
        val data = buildJsonObject { put("text", r.text) }
        return if (r.ok) BuiltinToolResult.success(message = r.text.take(MAX_MESSAGE), data = data)
        else BuiltinToolResult.failure(code = "TEAM_ERROR", message = r.text.take(MAX_MESSAGE), data = data)
    }

    private companion object { const val MAX_MESSAGE = 6_000 }
}

/** The shared task board: every handoff is a record with an owner, a status and a result. */
class BoardBuiltin : TeamForwardBuiltin(
    name = "board",
    description = "Shared task board for the agent team. op=add (title, detail, owner, workspace, parent, depth) creates a task for an agent; " +
        "op=next (owner) claims that agent's next TODO and marks it DOING; op=update (id, status=DONE|FAILED|BLOCKED|TODO, result) records the outcome; " +
        "op=list (workspace, open_only) shows the board; op=resume re-opens work a dead run left DOING. Always update an item when you finish it.",
    inputSchemaJson = """{"type":"object","properties":{"op":{"type":"string","enum":["add","next","update","list","resume"]},"id":{"type":"number"},"title":{"type":"string"},"detail":{"type":"string"},"owner":{"type":"string"},"workspace":{"type":"string"},"parent":{"type":"number"},"depth":{"type":"number"},"status":{"type":"string"},"result":{"type":"string"},"open_only":{"type":"boolean"}},"required":["op"]}""",
)

/** Engagement: daily caps, approval gates (tiered autonomy), human pacing and the action log. */
class EngageBuiltin : TeamForwardBuiltin(
    name = "engage",
    description = "Plan and record outward actions on a platform. op=plan (workspace, platform, action=post|note|comment|reply|like|restack|follow|dm, target, text, own_thread) " +
        "checks today's cap and returns id plus gate AUTO (go ahead: the owner already accepted autonomous work) or NEEDS_APPROVAL (only serious actions such as dm; pass serious=true for anything irreversible) or DENIED (cap reached). " +
        "op=done (id, note) / op=fail (id, note) record the result after you act. op=approve|reject (id, reason) is the owner's decision. " +
        "op=pending lists drafts waiting for the owner. op=today (workspace) shows counts versus caps. op=pace (min_s, max_s) returns a human-style wait in seconds; wait that long between actions.",
    inputSchemaJson = """{"type":"object","properties":{"op":{"type":"string","enum":["plan","done","fail","approve","reject","pending","today","pace"]},"id":{"type":"number"},"workspace":{"type":"string"},"platform":{"type":"string"},"action":{"type":"string"},"target":{"type":"string"},"text":{"type":"string"},"own_thread":{"type":"boolean"},"serious":{"type":"boolean"},"note":{"type":"string"},"reason":{"type":"string"},"min_s":{"type":"number"},"max_s":{"type":"number"}},"required":["op"]}""",
)

/** The owner's approved and rejected samples; the Reviewer scores new drafts against them. */
class ExamplesBuiltin : TeamForwardBuiltin(
    name = "examples",
    description = "Library of the owner's judged samples. op=list (platform) returns recent GOOD and BAD examples with reasons: read them before reviewing a draft. " +
        "op=add (platform, verdict=GOOD|BAD, text, reason) stores one. op=correct (platform, text, reason) records an owner correction as a BAD example and a standing rule, in one step.",
    inputSchemaJson = """{"type":"object","properties":{"op":{"type":"string","enum":["list","add","correct","remove"]},"id":{"type":"number"},"platform":{"type":"string"},"verdict":{"type":"string"},"kind":{"type":"string"},"text":{"type":"string"},"reason":{"type":"string"}},"required":["op"]}""",
)
