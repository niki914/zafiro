package com.niki914.zafiro.chat.agentic

import com.niki914.okia.ImageLoader
import com.niki914.zafiro.chat.agentic.image.IngestError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

/** IngestError → 工具错误码 JSON 的映射（供 ViewImageBuiltin 使用）。 */
internal fun IngestError.toToolError(): Pair<String, String> = when (this) {
    IngestError.FileNotFound -> "FILE_NOT_FOUND" to "Image file not found or is not a readable file."
    IngestError.EmptyContent -> "EMPTY_CONTENT" to "Image file is empty."
    is IngestError.TooLarge -> "IMAGE_TOO_LARGE" to "Image exceeds size limit (${bytes / 1024 / 1024}MB)."
    IngestError.UnsupportedFormat -> "UNSUPPORTED_FORMAT" to "Image format is not supported."
    is IngestError.DecodeFailed -> "DECODE_FAILED" to "Failed to decode image: ${cause?.message ?: "unknown"}"
    is IngestError.IoFailed -> "IO_FAILED" to "Failed to read image: ${cause?.message ?: "unknown"}"
    is IngestError.SvgRenderFailed -> "SVG_RENDER_FAILED" to "Failed to render SVG: ${cause?.message ?: "unknown"}"
}
