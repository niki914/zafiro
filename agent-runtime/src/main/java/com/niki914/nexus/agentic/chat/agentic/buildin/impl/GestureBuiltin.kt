package com.niki914.nexus.agentic.chat.agentic.buildin.impl

import com.niki914.nexus.agentic.chat.agentic.accessibility.AccessibilityController
import com.niki914.nexus.agentic.chat.agentic.accessibility.InteractionMethod
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

class GestureBuiltin : BuiltinTool() {
    override val name: String = "gesture"

    override val description: String =
        "Perform a swipe/drag gesture from (start_x, start_y) to (end_x, end_y). " +
            "Use accessibility method for reliable in-app gestures. " +
            "Use shell method when accessibility service is not connected or as fallback."

    override val defaultEnabled: Boolean = true

    override fun configure(config: LocalToolConfig) {
        config.description = description
        config.number("start_x") {
            description = "X coordinate of gesture start point."
            required = true
        }
        config.number("start_y") {
            description = "Y coordinate of gesture start point."
            required = true
        }
        config.number("end_x") {
            description = "X coordinate of gesture end point."
            required = true
        }
        config.number("end_y") {
            description = "Y coordinate of gesture end point."
            required = true
        }
        config.integer("duration") {
            description = "Gesture duration in milliseconds (default 300)."
            required = false
        }
        config.string("method") {
            description = "Interaction method: accessibility (default) or shell."
            required = false
        }
    }

    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
        val args = try {
            parseArguments(request.argumentsJson)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            return BuiltinToolResult.failure(
                code = "INVALID_ARGUMENTS_JSON",
                message = "gesture arguments must be a JSON object with start_x, start_y, end_x, end_y, and optional duration and method.",
                hint = """Example: {"start_x":100,"start_y":500,"end_x":300,"end_y":500,"duration":300}""",
                fieldErrors = mapOf("argumentsJson" to (throwable.message ?: "Invalid JSON object.")),
            )
        }

        val method = try {
            InteractionMethod.valueOf(args.method.uppercase())
        } catch (_: IllegalArgumentException) {
            return BuiltinToolResult.failure(
                code = "INVALID_METHOD",
                message = "Unknown method '${args.method}'. Valid methods: accessibility, shell.",
            )
        }

        return AccessibilityController.executeGesture(
            startX = args.startX,
            startY = args.startY,
            endX = args.endX,
            endY = args.endY,
            duration = args.duration,
            method = method,
        )
    }

    private fun parseArguments(argumentsJson: String): GestureArguments {
        val element = try {
            Json.parseToJsonElement(argumentsJson)
        } catch (throwable: SerializationException) {
            throw IllegalArgumentException("argumentsJson is not valid JSON.", throwable)
        } catch (throwable: IllegalArgumentException) {
            throw IllegalArgumentException("argumentsJson is not valid JSON.", throwable)
        }
        val obj = element as? JsonObject
            ?: throw IllegalArgumentException("argumentsJson must be a JSON object.")

        fun getFloat(key: String): Float {
            return obj[key]?.jsonPrimitive?.contentOrNull?.toFloatOrNull()
                ?: throw IllegalArgumentException("$key must be a valid number.")
        }

        return GestureArguments(
            startX = getFloat("start_x"),
            startY = getFloat("start_y"),
            endX = getFloat("end_x"),
            endY = getFloat("end_y"),
            duration = obj["duration"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 300L,
            method = obj["method"]?.jsonPrimitive?.contentOrNull.orEmpty().trim().ifBlank { "accessibility" },
        )
    }

    private data class GestureArguments(
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val duration: Long,
        val method: String,
    )
}
