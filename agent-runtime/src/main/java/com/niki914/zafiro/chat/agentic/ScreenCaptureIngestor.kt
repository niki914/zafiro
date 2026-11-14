package com.niki914.zafiro.chat.agentic

import com.niki914.logging.Logger
import com.niki914.zafiro.api.screen.PhoneScreenId
import com.niki914.zafiro.api.screen.ScreenImageUnavailable
import com.niki914.zafiro.api.screen.Screens
import com.niki914.zafiro.chat.agentic.image.IngestError
import com.niki914.zafiro.chat.agentic.image.IngestResult
import com.niki914.zafiro.service.findService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 唯一的「无障碍截屏 → 沙箱 ingest」链路，供应用层与工具层共用：
 * - 应用层 [captureAndIngest]（TextActionActivity）：只关心成功路径与失败阶段；
 * - 工具层 [captureForTool]：ingest 失败的原始错误随结果带回。
 *
 * 底层统一经由 [Screens] 契约的物理主屏获取 [com.niki914.zafiro.api.screen.ImageFrame]，
 * 并由 [SharedImageCodec] ingest 进 filesDir/image_cache/。
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

        val screens = findService<Screens>()
        val screen = screens?.get(PhoneScreenId) ?: run {
            Logger.w(TAG, "Screens service or Phone screen not available")
            return ToolCaptureResult.Err(
                Stage.ACCESSIBILITY_UNAVAILABLE,
                "Screen capture requires the Zafiro accessibility service.",
            )
        }

        val frame = try {
            screen.image()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ScreenImageUnavailable) {
            Logger.w(TAG, "screen.image failed code=${e.code} message=${e.message}")
            val stage = when (e.code) {
                "CODEC_UNAVAILABLE" -> Stage.CODEC_UNAVAILABLE
                "ACCESSIBILITY_UNAVAILABLE" -> Stage.ACCESSIBILITY_UNAVAILABLE
                else -> Stage.CAPTURE_FAILED
            }
            return ToolCaptureResult.Err(stage, e.message ?: e.code)
        } catch (t: Throwable) {
            Logger.w(TAG, "screen.image unexpected failure", t)
            return ToolCaptureResult.Err(Stage.CAPTURE_FAILED, t.message ?: "Screen capture failed.")
        }

        return when (val result = withContext(Dispatchers.IO) { codec.ingestBytes(frame.png, "image/png") }) {
            is IngestResult.Ok -> {
                Logger.i(TAG, "screen capture ingested successfully path=${result.image.path}")
                ToolCaptureResult.Ok(result)
            }
            is IngestResult.Err -> {
                Logger.w(TAG, "screen capture ingest failed error=${result.error}")
                ToolCaptureResult.IngestErr(result.error)
            }
        }
    }
}
