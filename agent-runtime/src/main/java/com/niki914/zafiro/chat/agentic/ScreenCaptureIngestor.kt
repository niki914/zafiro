package com.niki914.zafiro.chat.agentic

import android.graphics.Bitmap
import com.niki914.logging.Logger
import com.niki914.zafiro.chat.agentic.ScreenCaptureIngestor.captureAndIngest
import com.niki914.zafiro.chat.agentic.ScreenCaptureIngestor.captureForTool
import com.niki914.zafiro.chat.agentic.accessibility.AccessibilityController
import com.niki914.zafiro.chat.agentic.image.IngestError
import com.niki914.zafiro.chat.agentic.image.IngestResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * 唯一的「无障碍截屏 → PNG 编码 → 沙箱 ingest」链路，两个调用方共用：
 * - 工具层 [captureForTool]（screenshot 工具）：只做错误码映射，不自带抓图实现；
 * - 应用层 [captureAndIngest]（TextActionActivity）：只关心成功路径与失败阶段。
 *
 * 核心原则：
 * 1. 零中间临时文件：在内存中直接无损压缩为 PNG 字节，不经由 cacheDir 或 FileProvider；
 * 2. 直达私有沙箱：通过 [SharedImageCodec] ingest 进 filesDir/image_cache/，系统相册（MediaStore）不可见；
 * 3. 及时回收位图：原始截屏位图与 copy 出的软件位图在 finally 块中确定性回收，避免显存与内存泄漏。
 */
object ScreenCaptureIngestor {
    private const val TAG = "niki914_zafiro_ScreenCaptureIngestor"

    /** 失败阶段，按管线顺序排列。 */
    enum class Stage {
        ACCESSIBILITY_UNAVAILABLE,
        CODEC_UNAVAILABLE,
        CAPTURE_FAILED,
        ENCODE_FAILED,
        INGEST_FAILED,
    }

    /** 应用层结果：成功拿路径，失败拿阶段与描述。 */
    sealed interface CaptureResult {
        data class Ok(val path: String) : CaptureResult
        data class Err(val stage: Stage, val message: String?) : CaptureResult
    }

    /** 工具层结果：成功带完整 ingest 产物；ingest 失败带原始 [IngestError]，由调用方映射错误码。 */
    internal sealed interface ToolCaptureResult {
        data class Ok(val ingest: IngestResult.Ok) : ToolCaptureResult
        data class Err(val stage: Stage, val message: String?) : ToolCaptureResult
        data class IngestErr(val error: IngestError) : ToolCaptureResult
    }

    /** 截屏并写入沙箱，供应用层使用。 */
    suspend fun captureAndIngest(): CaptureResult = when (val result = run()) {
        is ToolCaptureResult.Ok -> CaptureResult.Ok(result.ingest.image.path)
        is ToolCaptureResult.Err -> CaptureResult.Err(result.stage, result.message)
        is ToolCaptureResult.IngestErr ->
            CaptureResult.Err(
                Stage.INGEST_FAILED,
                "Screen was captured but the image could not be processed."
            )
    }

    /** 截屏并写入沙箱，供工具层使用；ingest 失败的原始错误随结果带回。 */
    internal suspend fun captureForTool(): ToolCaptureResult = run()

    private suspend fun run(): ToolCaptureResult {
        val codec = SharedImageCodec.get() ?: run {
            Logger.w(TAG, "SharedImageCodec unavailable")
            return ToolCaptureResult.Err(Stage.CODEC_UNAVAILABLE, "Image codec is not available.")
        }

        AccessibilityController.ensureAccessibility().onFailure { error ->
            Logger.w(TAG, "ensureAccessibility failed: ${error.message}")
            return ToolCaptureResult.Err(
                Stage.ACCESSIBILITY_UNAVAILABLE,
                error.message ?: "Screen capture requires the Zafiro accessibility service.",
            )
        }

        val bitmap = AccessibilityController.captureScreenImage().getOrElse { error ->
            Logger.w(TAG, "captureScreenImage failed: ${error.message}")
            return ToolCaptureResult.Err(
                Stage.CAPTURE_FAILED,
                error.message ?: "Screen capture failed."
            )
        }

        return try {
            val bytes = encodePng(bitmap) ?: run {
                Logger.w(TAG, "encodePng failed")
                return ToolCaptureResult.Err(
                    Stage.ENCODE_FAILED,
                    "The captured frame could not be encoded."
                )
            }
            when (val result =
                withContext(Dispatchers.IO) { codec.ingestBytes(bytes, "image/png") }) {
                is IngestResult.Ok -> {
                    Logger.i(TAG, "screen capture ingested successfully path=${result.image.path}")
                    ToolCaptureResult.Ok(result)
                }

                is IngestResult.Err -> {
                    Logger.w(TAG, "screen capture ingest failed error=${result.error}")
                    ToolCaptureResult.IngestErr(result.error)
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * 官方截屏给出的是 HARDWARE 位图（像素不可直接读）：先拷成软件位图，再无损失编码为 PNG。
     * copy 出的软件位图与调用方传入的原始位图都在此回收。
     */
    private suspend fun encodePng(bitmap: Bitmap): ByteArray? = withContext(Dispatchers.Default) {
        try {
            val software = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return@withContext null
            try {
                ByteArrayOutputStream().use { out ->
                    if (!software.compress(
                            Bitmap.CompressFormat.PNG,
                            100,
                            out
                        )
                    ) return@withContext null
                    out.toByteArray()
                }
            } finally {
                software.recycle()
            }
        } catch (t: Throwable) {
            Logger.w(TAG, "encodePng error", t)
            null
        }
    }
}
