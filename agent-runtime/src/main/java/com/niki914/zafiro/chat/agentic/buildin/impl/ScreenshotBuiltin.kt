package com.niki914.zafiro.chat.agentic.buildin.impl

import com.niki914.xposed.api.util.ContextProvider
import com.niki914.zafiro.api.screen.ScreenImageUnavailable
import com.niki914.zafiro.chat.agentic.SharedImageCodec
import com.niki914.zafiro.chat.agentic.currentAgentConversationId
import com.niki914.zafiro.chat.agentic.buildin.BuiltinTool
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolResult
import com.niki914.zafiro.chat.agentic.image.IngestResult
import com.niki914.zafiro.chat.agentic.toImageToolResult
import com.niki914.zafiro.chat.agentic.toToolError

/**
 * 按 screen 取这一块屏自己的帧，并统一 ingest 写入沙箱图片缓存。
 */
class ScreenshotBuiltin : BuiltinTool() {
    override val name: String = "screenshot"
    override val defaultEnabled: Boolean = true
    override val description: String =
        "Capture a full screenshot image of the specified screen surface. Provide 'screen' " +
            "('phone' for default device screen, or an id from screens). Returns the ingested image " +
            "path and dimensions. Use view_image or vision analysis to inspect visual state."
    override val inputSchemaJson: String? = SCHEMA

    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
        val args = parseScreenObject(request.argumentsJson)
            ?: return BuiltinToolResult.failure(
                code = SCREEN_INVALID_JSON,
                message = "arguments must be a JSON object.",
            )
        val id = args.screenId()
            ?: return BuiltinToolResult.failure(
                code = SCREEN_INVALID_ARGUMENTS,
                message = "screen is required.",
            )
        val screens = screenDirectory()
            ?: return BuiltinToolResult.failure(
                code = SCREEN_SERVICE_UNAVAILABLE,
                message = "Screen service is not installed.",
            )
        val callerConvId = currentAgentConversationId()
        val (screen, failure) = resolveScreenOrFailure(screens, id, callerConvId)
        if (failure != null || screen == null) {
            return BuiltinToolResult.failure(
                code = failure?.code ?: SCREEN_UNKNOWN,
                message = failure?.message ?: "Unknown screen '${id.value}'.",
            )
        }
        val frame = try {
            screen.image()
        } catch (error: ScreenImageUnavailable) {
            return BuiltinToolResult.failure(
                code = error.code,
                message = error.message ?: error.code,
            )
        }
        if (ContextProvider.awaitIfAvailable() == null) {
            return BuiltinToolResult.failure(
                code = "CODEC_UNAVAILABLE",
                message = "Image codec is not available.",
            )
        }
        val codec = SharedImageCodec.get()
            ?: return BuiltinToolResult.failure(
                code = "CODEC_UNAVAILABLE",
                message = "Image codec is not available.",
            )
        return when (val ingested = codec.ingestBytes(frame.png, "image/png")) {
            is IngestResult.Ok -> ingested.toImageToolResult(
                message = "Screenshot captured: ${ingested.image.path}",
            )
            is IngestResult.Err -> {
                val (code, message) = ingested.error.toToolError()
                BuiltinToolResult.failure(code = code, message = message)
            }
        }
    }

    private companion object {
        private val SCHEMA = """
            {
              "type": "object",
              "properties": {
                "screen": {
                  "type": "string",
                  "description": "Target screen ID to capture, e.g. 'phone' for the default device display."
                },
                "ui_intent": {
                  "type": "string"
                }
              },
              "required": ["screen"]
            }
        """.trimIndent()
    }
}
