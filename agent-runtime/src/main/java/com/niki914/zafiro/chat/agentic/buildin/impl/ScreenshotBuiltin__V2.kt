package com.niki914.zafiro.chat.agentic.buildin.impl

import com.niki914.xposed.api.util.ContextProvider
import com.niki914.zafiro.chat.agentic.SharedImageCodec
import com.niki914.zafiro.chat.agentic.buildin.BuiltinTool
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolResult
import com.niki914.zafiro.chat.agentic.image.IngestResult
import com.niki914.zafiro.chat.agentic.toImageToolResult
import com.niki914.zafiro.chat.agentic.toToolError

/**
 * 按 screen 取这一块屏自己的帧。phone 的 image() 已经走完 capture-and-ingest 时，
 * 结果放在 [LastPhoneCapture]，这里直接交回去。
 */
class ScreenshotBuiltin__V2 : BuiltinTool() {
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
        val screen = screens.require(id)
            ?: return BuiltinToolResult.failure(
                code = SCREEN_UNKNOWN,
                message = "Unknown screen '${id.value}'.",
            )
        val frame = try {
            screen.image()
        } catch (error: ScreenImageUnavailable) {
            LastPhoneCapture.clear()
            return BuiltinToolResult.failure(
                code = error.code,
                message = error.message ?: error.code,
            )
        }
        LastPhoneCapture.take()?.let { return it }
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

/** phone 截屏失败时从 [com.niki914.zafiro.api.screen.Screen.image] 抛出，工具把它变成原来的错误码。 */
internal class ScreenImageUnavailable(
    val code: String,
    message: String,
) : RuntimeException(message)

// Phase 1 glue: connects to AccessibilityController directly; to be replaced in Phase 2 by DefaultDisplayPort.
/** phone 截屏成功时，ingest 结果已经在 captureForTool 里做完。跨挂起点用 volatile，不靠线程局部。 */
internal object LastPhoneCapture {
    @Volatile
    private var pending: BuiltinToolResult? = null

    fun offer(result: BuiltinToolResult) {
        pending = result
    }

    fun take(): BuiltinToolResult? {
        val result = pending
        pending = null
        return result
    }

    fun clear() {
        pending = null
    }
}
