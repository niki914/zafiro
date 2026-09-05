package com.niki914.zafiro.chat.agentic

import android.content.Context
import android.net.Uri
import com.niki914.okia.ImageLoader
import com.niki914.okia.ImageSaver
import com.niki914.zafiro.chat.agentic.image.ImageCodec
import com.niki914.zafiro.chat.agentic.image.IngestError
import com.niki914.zafiro.chat.agentic.image.IngestResult
import com.niki914.zafiro.chat.agentic.image.StoredImage
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

/**
 * Android ImageSaver 实现：base64 图片 → ImageCodec ingest → 落盘私有目录。
 * 入参允许 data URL（`data:image/png;base64,xxx`），实现负责 strip 前缀。
 * 统一存储路径：filesDir/Zafiro/images/（私有，零权限）。
 */
class AndroidImageSaver(context: Context) : ImageSaver {
    private val codec = ImageCodec(context.applicationContext)

    override suspend fun save(base64: String): String? {
        return when (val result = codec.ingestBase64(base64)) {
            is IngestResult.Ok -> result.image.path
            is IngestResult.Err -> null
        }
    }
}

/**
 * 用户分享图片（content URI）→ ImageCodec ingest → 落盘私有目录 → 返回路径。
 * 供分享入口调用。URI grant 授权是临时的，ingest 即转存私有目录是正确性要求。
 */
class UserImageSaver(private val context: Context) {
    private val codec = ImageCodec(context.applicationContext)

    /** Ingest 并返回落盘路径 + 预览 data URL（小图 JPEG base64，供 UI 直接解码）。 */
    suspend fun ingestFromUri(uri: Uri): IngestedImage? {
        return when (val result = codec.ingestUri(uri)) {
            is IngestResult.Ok -> IngestedImage(
                path = result.image.path,
                dataUrl = encodeDataUrl(result.image),
            )
            is IngestResult.Err -> null
        }
    }

    private suspend fun encodeDataUrl(image: StoredImage): String = withContext(Dispatchers.IO) {
        val bytes = File(image.path).readBytes()
        "data:image/jpeg;base64,${android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)}"
    }
}

/** ingest 成功产出：落盘路径 + UI 预览 data URL。 */
data class IngestedImage(val path: String, val dataUrl: String)

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
