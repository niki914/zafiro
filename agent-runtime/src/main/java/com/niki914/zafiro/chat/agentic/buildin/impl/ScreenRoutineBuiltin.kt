package com.niki914.zafiro.chat.agentic.buildin.impl

import com.niki914.xposed.api.util.ContextProvider
import com.niki914.zafiro.api.screen.ScreenId
import com.niki914.zafiro.chat.agentic.buildin.BuiltinTool
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolResult
import com.niki914.zafiro.chat.agentic.routine.RoutineEngine
import com.niki914.zafiro.chat.agentic.routine.RoutineParser
import com.niki914.zafiro.chat.agentic.routine.RoutineResult
import com.niki914.zafiro.chat.agentic.routine.RoutineStore
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * Runs a whole screen routine (post a note, reply, read activity, collect a feed...) in ONE call, with every tap verified.
 * Routines are JSON files in files/routines/, so the owner or an agent can fix a screen label without rebuilding the app.
 */
class ScreenRoutineBuiltin(private val dirProvider: suspend () -> File = { File(ContextProvider.await().applicationContext.filesDir, "routines") }) : BuiltinTool() {
    override val name: String = "screen_routine"
    override val defaultEnabled: Boolean = true
    override val description: String =
        "Run a saved screen routine in one call instead of tapping step by step. routine=list shows available routines and their params. " +
            "routine=<id> with params={...} runs it: every tap is verified (screen must change; one retry; then a clear failure). " +
            "You can also pass steps=[...] for a one-off sequence (ops: open_app, tap, type, wait, scroll, back, key, collect, sleep). " +
            "The result lists what is on screen afterwards: confirm success from that evidence, never from ok alone. " +
            "Routine files live in the files/routines folder; fix wrong screen labels there."
    override val inputSchemaJson: String =
        """{"type":"object","properties":{"routine":{"type":"string","description":"Routine id, or 'list'. Omit when passing steps."},"params":{"type":"object","description":"Values for {placeholders} in the routine, e.g. {\"text\":\"...\"}"},"steps":{"type":"array","items":{"type":"object"},"description":"One-off steps: {op, labels[], expect_any[], expect_gone[], optional, text, package, count, timeout_s, code, ms}"},"screen":{"type":"string","description":"Screen id, default phone."}}}"""

    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
        val args = parseScreenObject(request.argumentsJson)
            ?: return BuiltinToolResult.failure(code = "INVALID_ARGUMENTS", message = "Arguments must be a JSON object.")
        val loaded = RoutineStore(dirProvider()).load()
        val id = args.text("routine")
        if (id == "list") return list(loaded)

        val steps = try {
            when {
                args["steps"] is JsonArray -> RoutineParser.parseSteps(args["steps"] as JsonArray)
                id != null -> loaded.routines[id]?.steps ?: return BuiltinToolResult.failure(
                    code = "UNKNOWN_ROUTINE", message = "No routine '$id'.",
                    hint = "Use routine=list. Broken files: ${loaded.errors.ifEmpty { "none" }}",
                )
                else -> return BuiltinToolResult.failure(code = "INVALID_ARGUMENTS", message = "Pass routine or steps.")
            }
        } catch (e: RoutineParser.Invalid) {
            return BuiltinToolResult.failure(code = "INVALID_ROUTINE", message = e.message.orEmpty())
        }

        val params = (args["params"] as? JsonObject)?.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull ?: v.toString() }.orEmpty()
        val missing = loaded.routines[id]?.params?.filter { it.required && params[it.name].isNullOrBlank() }?.map { it.name }.orEmpty()
        if (missing.isNotEmpty()) return BuiltinToolResult.failure(code = "MISSING_PARAMS", message = "Missing params: ${missing.joinToString()}.")

        val screens = screenDirectory() ?: return BuiltinToolResult.failure(code = SCREEN_SERVICE_UNAVAILABLE, message = "Screen service is not installed.")
        val screen = screens.get(ScreenId(args.text("screen") ?: "phone")) ?: return BuiltinToolResult.failure(code = SCREEN_UNKNOWN, message = "Unknown screen.")
        val result = RoutineEngine(screen).run(id ?: "adhoc", steps, params)
        return toResult(result)
    }

    private fun list(l: com.niki914.zafiro.chat.agentic.routine.RoutineStore.Loaded) = BuiltinToolResult.success(
        message = "${l.routines.size} routine(s).",
        data = buildJsonObject {
            put("routines", buildJsonArray {
                l.routines.values.forEach { r ->
                    add(buildJsonObject {
                        put("id", r.id); put("platform", r.platform); put("description", r.description)
                        put("params", r.params.joinToString { if (it.required) it.name else it.name + "?" })
                    })
                }
            })
            if (l.errors.isNotEmpty()) put("broken_files", l.errors.entries.joinToString("; ") { "${it.key}: ${it.value}" })
        },
    )

    private fun toResult(r: RoutineResult): BuiltinToolResult {
        val data = buildJsonObject {
            put("ok", r.ok); put("routine", r.routine); put("steps_done", r.stepsDone); put("total_steps", r.totalSteps)
            r.failedStep?.let { put("failed_step", it) }
            if (r.reason.isNotEmpty()) put("reason", r.reason)
            put("screen_changed", r.screenChanged)
            put("on_screen_now", buildJsonArray { r.evidence.forEach { add(JsonPrimitive(it)) } })
            if (r.collected.isNotEmpty()) put("collected", buildJsonArray { r.collected.forEach { add(JsonPrimitive(it)) } })
        }
        return if (r.ok) BuiltinToolResult.success(message = "Routine ${r.routine}: ${r.stepsDone}/${r.totalSteps} steps done. Check on_screen_now to confirm the result.", data = data)
        else BuiltinToolResult.failure(
            code = "ROUTINE_FAILED", message = r.reason, data = data,
            hint = "Do not loop. Read the screen once, then either fix the routine file's labels, take another route, or report the failure.",
        )
    }
}
