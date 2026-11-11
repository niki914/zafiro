package com.niki914.zafiro.chat.agentic.buildin.impl

import com.niki914.zafiro.chat.agentic.buildin.BuiltinTool
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Hand the phone to the owner (login, CAPTCHA, 2FA, payment, consent). Posts a notification and tells the agent to stop and
 * report STATUS: NEEDS_HUMAN, which scheduled runs record as "needs you". It never tries to get past the obstacle itself.
 */
class TakeOverBuiltin : BuiltinTool() {
    override val name: String = "take_over"
    override val description: String =
        "Ask the human to take over when only they can continue (login, CAPTCHA, 2FA code, payment, consent screen). " +
            "Notifies them, then you must stop working and end your report with 'STATUS: NEEDS_HUMAN - <what they must do>'."
    override val defaultEnabled: Boolean = true
    override val inputSchemaJson: String =
        """{"type":"object","properties":{"reason":{"type":"string","description":"what the human must do"}},"required":["reason"]}"""

    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
        val reason = runCatching { (Json.parseToJsonElement(request.argumentsJson) as? JsonObject)?.get("reason")?.jsonPrimitive?.contentOrNull }
            .getOrNull()?.trim().orEmpty()
        if (reason.isEmpty()) {
            return BuiltinToolResult.failure(code = "INVALID_ARGUMENTS", message = "take_over needs a 'reason'.", hint = """Example: {"reason":"Solve the CAPTCHA on the login page"}""")
        }
        val payload = buildJsonObject {
            put("title", "Zafiro needs you")
            put("content", reason.take(300))
        }
        val posted = NotifyBuiltin().invoke(BuiltinToolRequest(name = "notify", argumentsJson = payload.toString()))
        return BuiltinToolResult.success(
            message = if (posted.ok) "The human was notified." else "Could not notify the human, but you must still stop.",
            hint = "STOP now. Do not try to bypass the obstacle. End your final report with: STATUS: NEEDS_HUMAN - $reason",
            data = buildJsonObject { put("notified", posted.ok) },
        )
    }
}
