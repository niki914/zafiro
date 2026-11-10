package com.niki914.zafiro.chat.agentic.buildin.impl

import com.niki914.zafiro.chat.agentic.buildin.BuiltinTool
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolResult
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Pause between steps (apps need time to load). Capped so a wrong value can never stall a run; cancellable. */
class WaitBuiltin : BuiltinTool() {
    override val name: String = "wait"
    override val description: String =
        "Wait for a number of seconds (0.5 to $MAX_SECONDS) before the next step, e.g. while an app or page loads."
    override val defaultEnabled: Boolean = true
    override val inputSchemaJson: String =
        """{"type":"object","properties":{"seconds":{"type":"number","description":"0.5 to $MAX_SECONDS"}},"required":["seconds"]}"""

    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
        val seconds = parseSeconds(request.argumentsJson)
            ?: return BuiltinToolResult.failure(
                code = "INVALID_ARGUMENTS",
                message = "wait needs a numeric 'seconds' between $MIN_SECONDS and $MAX_SECONDS.",
                hint = """Example: {"seconds":2}""",
            )
        delay((seconds * 1000).toLong())
        return BuiltinToolResult.success(
            message = "Waited $seconds seconds.",
            data = buildJsonObject { put("seconds", seconds) },
        )
    }

    internal companion object {
        const val MIN_SECONDS = 0.5
        const val MAX_SECONDS = 60.0

        fun parseSeconds(argumentsJson: String): Double? {
            val value = runCatching {
                (Json.parseToJsonElement(argumentsJson) as? JsonObject)?.get("seconds")?.jsonPrimitive?.doubleOrNull
            }.getOrNull() ?: return null
            return value.takeIf { it in MIN_SECONDS..MAX_SECONDS }
        }
    }
}
