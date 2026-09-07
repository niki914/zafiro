package com.niki914.zafiro.chat.agentic

import com.niki914.okia.ImageLoader
import com.niki914.xposed.api.util.ContextProvider
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolResult
import com.niki914.zafiro.chat.agentic.image.IngestError
import com.niki914.zafiro.chat.agentic.image.IngestResult
import com.niki914.zafiro.chat.agentic.image.ImageCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * Android ImageLoader 实现：从文件系统读取图片字节（suspend + IO dispatcher）。
 * 返回 null = 文件不存在或不可读（外部存储被用户删除等场景）。
 * 护栏：文件 ≤12MB（纵深防御，ingest 已保证落盘图小，但历史路径无保证）。
 */
class AndroidImageLoader(
    private val maxBytes: Int = com.niki914.zafiro.chat.agentic.image.ImageFormat.MAX_IMAGE_BYTES,
) : ImageLoader {
    override suspend fun load(path: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val file = File(path)
            if (!file.exists() || !file.isFile) return@withContext null
            if (file.length() > maxBytes.toLong()) return@withContext null
            file.readBytes()
        } catch (e: Exception) {
            null
        }
    }
}

/** ingest 成功产出：落盘路径（mimeType 由 ImageCodec 管线保证恒为 image/jpeg，不重复携带）。 */
data class IngestedImage(val path: String)

/**
 * 图片类内置工具（view_image / screenshot）共享的 codec 惰性单例：
 * 进程内缓存一个 ImageCodec 实例，避免每次调用重复构建。
 */
internal object SharedImageCodec {
    @Volatile
    private var codec: ImageCodec? = null

    suspend fun get(): ImageCodec? {
        codec?.let { return it }
        val context = try {
            ContextProvider.await().applicationContext
        } catch (e: Exception) {
            return null
        } ?: return null
        return ImageCodec(context).also { codec = it }
    }
}

/** ingest 成功 → 与 view_image/screenshot 共用的 data.image JSON 结果（契约两工具一致）。 */
internal fun IngestResult.Ok.toImageToolResult(message: String): BuiltinToolResult = BuiltinToolResult.success(
    message = message,
    data = JsonObject(
        mapOf(
            "image" to JsonObject(
                mapOf(
                    "path" to JsonPrimitive(image.path),
                    "mime_type" to JsonPrimitive(image.mimeType),
                    "width" to JsonPrimitive(image.width),
                    "height" to JsonPrimitive(image.height),
                    "bytes" to JsonPrimitive(image.bytes),
                )
            )
        )
    )
)

/** IngestError → 工具错误码 JSON 的映射（供 ViewImageBuiltin / ScreenshotBuiltin 使用）。 */
internal fun IngestError.toToolError(): Pair<String, String> = when (this) {
    IngestError.FileNotFound -> "FILE_NOT_FOUND" to "Image file not found or is not a readable file."
    IngestError.EmptyContent -> "EMPTY_CONTENT" to "Image file is empty."
    is IngestError.TooLarge -> "IMAGE_TOO_LARGE" to "Image exceeds size limit (${bytes / 1024 / 1024}MB)."
    IngestError.UnsupportedFormat -> "UNSUPPORTED_FORMAT" to "Image format is not supported."
    is IngestError.DecodeFailed -> "DECODE_FAILED" to "Failed to decode image: ${cause?.message ?: "unknown"}"
    is IngestError.IoFailed -> "IO_FAILED" to "Failed to read image: ${cause?.message ?: "unknown"}"
    is IngestError.SvgRenderFailed -> "SVG_RENDER_FAILED" to "Failed to render SVG: ${cause?.message ?: "unknown"}"
}
