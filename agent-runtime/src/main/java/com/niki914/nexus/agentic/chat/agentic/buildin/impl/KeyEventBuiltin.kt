package com.niki914.nexus.agentic.chat.agentic.buildin.impl

import com.niki914.nexus.agentic.chat.agentic.accessibility.AccessibilityController
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinTool
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolResult
import com.niki914.s3ss10n.LocalToolConfig
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class KeyEventBuiltin : BuiltinTool() {
    override val name: String = "key_event"

    override val description: String =
        "Perform a system key event by Android key code. " +
            "Standard key codes: BACK=4, HOME=3, RECENTS/APP_SWITCH=187, NOTIFICATIONS=83, QUICK_SETTINGS=84. " +
            "Numeric key codes for other keys (volume, camera, etc.) are also supported."

    override val defaultEnabled: Boolean = true

    override fun configure(config: LocalToolConfig) {
        config.description = description
        config.integer("key") {
            description = "Android key code to inject (e.g., 4=BACK, 3=HOME, 187=APP_SWITCH)."
            required = true
        }
    }

    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
        val keyCode = try {
            parseArguments(request.argumentsJson)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            return BuiltinToolResult.failure(
                code = "INVALID_ARGUMENTS_JSON",
                message = "key_event arguments must be a JSON object with a key field.",
                hint = """Example: {"key":4} for BACK""",
                fieldErrors = mapOf("argumentsJson" to (throwable.message ?: "Invalid JSON object.")),
            )
        }

        return AccessibilityController.executeKeyEvent(keyCode)
    }

    private fun parseArguments(argumentsJson: String): Int {
        val element = try {
            Json.parseToJsonElement(argumentsJson)
        } catch (throwable: SerializationException) {
            throw IllegalArgumentException("argumentsJson is not valid JSON.", throwable)
        } catch (throwable: IllegalArgumentException) {
            throw IllegalArgumentException("argumentsJson is not valid JSON.", throwable)
        }
        val obj = element as? JsonObject
            ?: throw IllegalArgumentException("argumentsJson must be a JSON object.")
        val keyPrimitive = obj["key"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("key field is required.")
        return keyPrimitive.toIntOrNull()
            ?: throw IllegalArgumentException("key must be a valid integer.")
    }
}
