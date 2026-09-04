package com.niki914.zafiro.chat.agentic.buildin.impl

import com.niki914.zafiro.chat.agentic.buildin.BuiltinTool
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * view_image 工具：Agent 主动读取磁盘上的图片文件。
 * 参数 path = 图片文件绝对路径，返回图片文件路径（供会话树引用）。
 * 统一存储路径：App 私有目录（filesDir/Zafiro/images）。
 *
 * 文件不存在时返回错误，由 Agent 决定后续操作（如提示用户文件已删除）。
 */
class ViewImageBuiltin : BuiltinTool() {
    override val name: String = "view_image"
    override val description: String = """
Read an image file from disk so the model can see it.
Use when you need to view an image that the user shared, downloaded from the web, or saved by a tool.
Accepts an absolute file path (e.g. a path returned by py_download_file or the images directory).
Returns the file path if the file exists, or an error if it was deleted or is unreadable.
    """.trimIndent()
    override val defaultEnabled: Boolean = true
    override val inputSchemaJson: String? = SCHEMA

    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
        val args = request.argumentsJson
        val path = try {
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(args).jsonObject
            (obj["path"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.trim().orEmpty()
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

        val file = File(path)
        if (!file.exists() || !file.isFile) {
            return BuiltinToolResult.failure(
                code = "FILE_NOT_FOUND",
                message = "Image file not found or is not a readable file: $path",
                hint = "The file may have been deleted. Ask the user to share or download it again."
            )
        }

        val mimeType = when (file.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            else -> "image/jpeg"
        }

        return BuiltinToolResult.success(
            message = "Image file verified: $path",
            data = JsonObject(
                mapOf(
                    "image" to JsonObject(
                        mapOf(
                            "path" to kotlinx.serialization.json.JsonPrimitive(path),
                            "mime_type" to kotlinx.serialization.json.JsonPrimitive(mimeType)
                        )
                    )
                )
            )
        )
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
