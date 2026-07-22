package com.niki914.nexus.agentic.chat.agentic.buildin.impl

import com.niki914.nexus.agentic.chat.agentic.accessibility.AccessibilityController
import com.niki914.nexus.agentic.chat.agentic.accessibility.InteractionMethod
import com.niki914.nexus.agentic.chat.agentic.accessibility.NodeAction
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

class NodeActionBuiltin : BuiltinTool() {
    override val name: String = "node_action"

    override val description: String =
        "Perform an action on a UI node identified by its index from screen_content. " +
                "Supports methods: accessibility (default) for set_text (only method that can type into text fields) " +
                "and shell for tap/long_click/scroll via root input commands. " +
                "Actions: click, long_click, set_text, scroll_forward, scroll_backward.\n\n" +
                "scroll_forward/scroll_backward move 1 step per call, but step size varies unpredictably per app. " +
                "Prefer gesture() for list scrolling. Use scroll_forward/scroll_backward only for single-step increments in pickers or small widgets."

    override val defaultEnabled: Boolean = true

    override fun configure(config: LocalToolConfig) {
        config.description = description
        config.string("action") {
            description =
                "Action to perform: click, long_click, set_text, scroll_forward, scroll_backward."
            required = true
        }
        config.integer("index") {
            description = "Node index from screen_content output."
            required = true
        }
        config.string("text") {
            description = "Text to set when action is set_text."
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
                message = "node_action arguments must be a JSON object with action, index, and optional text and method.",
                hint = """Example: {"action":"click","index":42} or {"action":"set_text","index":7,"text":"hello"}""",
                fieldErrors = mapOf(
                    "argumentsJson" to (throwable.message ?: "Invalid JSON object.")
                ),
            )
        }

        val action = try {
            NodeAction.valueOf(args.action.uppercase())
        } catch (_: IllegalArgumentException) {
            return BuiltinToolResult.failure(
                code = "INVALID_ACTION",
                message = "Unknown action '${args.action}'. Valid actions: click, long_click, set_text, scroll_forward, scroll_backward.",
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

        return AccessibilityController.executeNodeAction(
            index = args.index,
            action = action,
            text = args.text,
            method = method,
        )
    }

    private fun parseArguments(argumentsJson: String): NodeActionArguments {
        val element = try {
            Json.parseToJsonElement(argumentsJson)
        } catch (throwable: SerializationException) {
            throw IllegalArgumentException("argumentsJson is not valid JSON.", throwable)
        } catch (throwable: IllegalArgumentException) {
            throw IllegalArgumentException("argumentsJson is not valid JSON.", throwable)
        }
        val obj = element as? JsonObject
            ?: throw IllegalArgumentException("argumentsJson must be a JSON object.")
        return NodeActionArguments(
            action = obj["action"]?.jsonPrimitive?.contentOrNull.orEmpty().trim(),
            index = obj["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                ?: throw IllegalArgumentException("index must be a valid integer."),
            text = obj["text"]?.jsonPrimitive?.contentOrNull?.trim().takeIf { !it.isNullOrBlank() },
            method = obj["method"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
                .ifBlank { "accessibility" },
        )
    }

    private data class NodeActionArguments(
        val action: String,
        val index: Int,
        val text: String?,
        val method: String,
    )
}
