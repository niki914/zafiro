package com.niki914.zafiro.chat.agentic.buildin.impl

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.niki914.xposed.api.util.ContextProvider
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
 * Copy text to, or read text from, the clipboard. Android only lets the app in the FOREGROUND read the clipboard,
 * so unattended reads can come back empty; that is reported honestly instead of pretending the clipboard is empty.
 */
class ClipboardBuiltin : BuiltinTool() {
    override val name: String = "clipboard"
    override val description: String =
        "Write text to the clipboard (action=write) or read the current clipboard text (action=read). " +
            "Reading only works while Zafiro is on screen; in the background Android returns nothing."
    override val defaultEnabled: Boolean = true
    override val inputSchemaJson: String =
        """{"type":"object","properties":{"action":{"type":"string","enum":["read","write"]},"text":{"type":"string","description":"required for write"}},"required":["action"]}"""

    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
        val args = runCatching { Json.parseToJsonElement(request.argumentsJson) as? JsonObject }.getOrNull()
            ?: return invalid("Arguments must be a JSON object.")
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.lowercase()
        val manager = ContextProvider.await().applicationContext
            .getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return BuiltinToolResult.failure(code = "CLIPBOARD_UNAVAILABLE", message = "No clipboard service on this device.")
        return when (action) {
            "write" -> {
                val text = args["text"]?.jsonPrimitive?.contentOrNull
                if (text.isNullOrEmpty()) return invalid("write needs a non-empty 'text'.")
                manager.setPrimaryClip(ClipData.newPlainText("Zafiro", text))
                BuiltinToolResult.success(message = "Copied ${text.length} characters to the clipboard.")
            }
            "read" -> {
                val text = runCatching { manager.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(null)?.toString() }.getOrNull()
                if (text.isNullOrEmpty()) {
                    BuiltinToolResult.success(
                        message = "The clipboard looks empty, or Android hid it because Zafiro is not on screen.",
                        data = buildJsonObject { put("text", "") },
                    )
                } else {
                    BuiltinToolResult.success(message = "Clipboard read.", data = buildJsonObject { put("text", text.take(MAX_READ_CHARS)) })
                }
            }
            else -> invalid("'action' must be \"read\" or \"write\".")
        }
    }

    private fun invalid(message: String) = BuiltinToolResult.failure(
        code = "INVALID_ARGUMENTS",
        message = message,
        hint = """Example: {"action":"write","text":"hello"}""",
    )

    private companion object {
        const val MAX_READ_CHARS = 20_000
    }
}
