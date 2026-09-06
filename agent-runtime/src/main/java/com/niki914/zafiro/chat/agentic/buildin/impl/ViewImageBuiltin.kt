package com.niki914.zafiro.chat.agentic.buildin.impl

import com.niki914.xposed.api.util.ContextProvider
import kotlin.concurrent.Volatile
import com.niki914.zafiro.chat.agentic.buildin.BuiltinTool
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolResult
import com.niki914.zafiro.chat.agentic.image.ImageCodec
import com.niki914.zafiro.chat.agentic.image.IngestError
import com.niki914.zafiro.chat.agentic.image.IngestResult
import com.niki914.zafiro.chat.agentic.toToolError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * view_image 工具：Agent 主动读取磁盘上的图片文件。
 * 参数 path = 图片文件绝对路径，返回 ingest 后的图片文件路径（供会话树引用）。
 * 统一存储路径：App 私有目录（files/image_cache）。
 *
 * ingest 失败时返回结构化错误码，由 Agent 决定后续操作。
 */
class ViewImageBuiltin : BuiltinTool() {
    @Volatile
    private var codec: ImageCodec? = null

    private suspend fun ensureCodec(): ImageCodec? {
        codec?.let { return it }
        val context = try {
            ContextProvider.await().applicationContext
        } catch (e: Exception) {
            return null
        } ?: return null
        return ImageCodec(context).also { codec = it }
    }
    override val name: String = "view_image"
    override val description: String = """
Read an image file from disk so the model can see it.
Use when you need to view an image that the user shared, downloaded from the web, or saved by a tool.
Accepts an absolute file path (e.g. a path returned by py_download_file). Paths inside app private storage (see Environment) are always readable; external paths may fail without storage permission.
Returns the normalized file path if the image was ingested successfully, or an error code if it was deleted, unreadable, too large, or in an unsupported format.
    """.trimIndent()
    override val defaultEnabled: Boolean = true
    override val inputSchemaJson: String? = SCHEMA

    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
        val args = request.argumentsJson
        val path = try {
            val obj = Json.parseToJsonElement(args).jsonObject
            (obj["path"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        } catch (e: Exception) {
            return BuiltinToolResult.failure(
                code = "INVALID_ARGUMENTS",
                message = "Failed to parse arguments: ${e.message}"
            )
        }

        if (path.isBlank()) {
            return BuiltinToolResult.failure(
                code = "MISSING_PATH",
                message = "Field 'path' is required and must be a non-empty string."
            )
        }

        val codec = ensureCodec() ?: return BuiltinToolResult.failure(
            code = "CODEC_UNAVAILABLE",
            message = "Image codec is not available.",
            hint = "Application context is not initialized yet. Retry later."
        )
        return when (val result = codec.ingestFile(path)) {
            is IngestResult.Ok -> BuiltinToolResult.success(
                message = "Image ingested: ${result.image.path}",
                data = JsonObject(
                    mapOf(
                        "image" to JsonObject(
                            mapOf(
                                "path" to JsonPrimitive(result.image.path),
                                "mime_type" to JsonPrimitive(result.image.mimeType),
                                "width" to JsonPrimitive(result.image.width),
                                "height" to JsonPrimitive(result.image.height),
                                "bytes" to JsonPrimitive(result.image.bytes),
                            )
                        )
                    )
                )
            )
            is IngestResult.Err -> {
                val (code, message) = result.error.toToolError()
                val hint = when (result.error) {
                    IngestError.FileNotFound ->
                        "The file may have been deleted. If the path is outside app private " +
                                "storage (e.g. /sdcard), reading it may require storage permission " +
                                "the app does not have - copy the file into app private storage " +
                                "first (e.g. with a root shell), or ask the user to grant storage " +
                                "permission, or download it again into a private directory."
                    IngestError.EmptyContent -> "The file is empty. Check the source and re-download it."
                    is IngestError.TooLarge -> "Try a smaller image or compress it first."
                    IngestError.UnsupportedFormat -> "Convert the image to JPEG or PNG first."
                    is IngestError.DecodeFailed -> "The file exists but is not a decodable image. Verify the file is complete and is an actual image."
                    is IngestError.IoFailed -> "The file could not be read. Check permissions and retry."
                    is IngestError.SvgRenderFailed -> "The SVG is invalid or too complex to render. Verify the SVG content."
                }
                BuiltinToolResult.failure(
                    code = code,
                    message = message,
                    hint = hint
                )
            }
        }
    }

    companion object {
        private val SCHEMA = """
{
  "type": "object",
  "properties": {
    "path": {
      "type": "string",
      "description": "Absolute path to the image file (e.g. a path returned by py_download_file)."
    }
  },
  "required": ["path"]
}
        """.trimIndent()
    }
}
