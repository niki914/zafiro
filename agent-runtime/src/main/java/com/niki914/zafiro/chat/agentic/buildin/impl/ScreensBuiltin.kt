package com.niki914.zafiro.chat.agentic.buildin.impl

import com.niki914.zafiro.api.screen.CloseResult
import com.niki914.zafiro.api.screen.OpenKind
import com.niki914.zafiro.api.screen.OpenResult
import com.niki914.zafiro.api.screen.ScreenInfo
import com.niki914.zafiro.api.screen.Screens
import com.niki914.zafiro.chat.agentic.currentAgentConversationId
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.TextResultBuiltinTool
import com.niki914.zafiro.chat.agentic.buildin.TextToolResult
import kotlinx.serialization.json.JsonObject

/**
 * 列出、打开、关闭屏。
 * `open` 的 kind 原样交给 [com.niki914.zafiro.api.screen.Screens.open]。
 */
class ScreensBuiltin : TextResultBuiltinTool() {
    override val name: String = "screens"
    override val defaultEnabled: Boolean = true
    override val description: String =
        "Manage screen surfaces. Operations: 'list' (list all active screens with their id, " +
            "kind, and dimensions), 'open' (create a new 'virtual_display' or 'browser' screen), " +
            "'close' (destroy an active screen other than 'phone'). Use the returned screen id with " +
            "screen_operation, screenshot, and open."
    override val inputSchemaJson: String? = SCHEMA

    override suspend fun invokeText(request: BuiltinToolRequest): TextToolResult {
        val args = parseScreenObject(request.argumentsJson)
            ?: return TextToolResult.failure(
                code = SCREEN_INVALID_JSON,
                message = "arguments must be a JSON object.",
            )
        val operation = args.text("operation") ?: return invalidArguments("operation is required.")
        val screens = screenDirectory() ?: return missingService()
        val callerConvId = currentAgentConversationId()
        return when (operation) {
            "list" -> TextToolResult.success(screens.list(callerConvId).joinToString("\n") { it.encode() })
            "open" -> open(screens, args.text("kind"), callerConvId)
            "close" -> close(screens, args, callerConvId)
            else -> TextToolResult.failure(
                code = SCREEN_INVALID_OPERATION,
                message = "Unknown operation '$operation'.",
            )
        }
    }

    private suspend fun open(screens: Screens, kind: String?, callerConversationId: String?): TextToolResult {
        val openKind = when (kind) {
            "virtual_display" -> OpenKind.VirtualDisplay
            "browser" -> OpenKind.Browser
            else -> return invalidArguments("kind must be virtual_display or browser.")
        }
        return when (val opened = screens.open(openKind, callerConversationId)) {
            is OpenResult.Opened -> TextToolResult.success(opened.info.encode())
            is OpenResult.Unsupported -> TextToolResult.failure(
                code = SCREEN_UNSUPPORTED,
                message = "Opening ${opened.kind} is not available.",
            )
            is OpenResult.Rejected -> TextToolResult.failure(
                code = opened.code,
                message = opened.code,
            )
        }
    }

    private suspend fun close(screens: Screens, args: JsonObject, callerConversationId: String?): TextToolResult {
        val id = args.screenId() ?: return invalidArguments("screen is required.")
        return when (val closed = screens.close(id, callerConversationId)) {
            CloseResult.Closed -> TextToolResult.success(id.value)
            is CloseResult.Rejected -> TextToolResult.failure(
                code = closed.code,
                message = closed.code,
            )
        }
    }

    private companion object {
        private val SCHEMA = """
            {
              "type": "object",
              "properties": {
                "operation": {
                  "type": "string",
                  "description": "Operation to perform: 'list' (list all screens), 'open' (open new screen surface), 'close' (close an existing screen)."
                },
                "kind": {
                  "type": "string",
                  "description": "Screen kind to open: 'virtual_display' or 'browser'. Required when operation is 'open'."
                },
                "screen": {
                  "type": "string",
                  "description": "Screen ID to close (e.g. 'vd_1'). Main device screen 'phone' cannot be closed. Required when operation is 'close'."
                },
                "ui_intent": {
                  "type": "string"
                }
              },
              "required": ["operation"]
            }
        """.trimIndent()
    }
}

private fun ScreenInfo.encode(): String {
    return "${id.value}\t${kind}\t$width\t$height"
}
