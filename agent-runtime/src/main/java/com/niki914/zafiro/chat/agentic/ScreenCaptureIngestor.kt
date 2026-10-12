package com.niki914.zafiro.chat.agentic

import android.graphics.Bitmap
import com.niki914.logging.Logger
import com.niki914.zafiro.chat.agentic.accessibility.AccessibilityController
import com.niki914.zafiro.chat.agentic.image.IngestResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * 无障碍屏幕截取与沙箱直接 ingest 工具。
 *
 * 核心原则：
 * 1. 零中间临时文件：不经由 cacheDir 或 FileProvider，在内存中直接无损压缩为 PNG 字节；
 * 2. 直达私有沙箱：通过 [SharedImageCodec] ingest 进 filesDir/image_cache/，系统相册（MediaStore）不可见；
 * 3. 及时回收位图：中间位图和原始截屏位图在 finally 块中确定性回收，避免显存与内存泄漏。
 */
object ScreenCaptureIngestor {
    private const val TAG = "niki914_zafiro_ScreenCaptureIngestor"

    /**
     * 截取屏幕画面并直接写入应用私有沙箱图片库。
     *
     * @return 成功写入的沙箱文件绝对路径，失败或无权限返回 null。
     */
    suspend fun captureAndIngest(): String? {
        val bitmap = AccessibilityController.captureScreenImage().getOrNull() ?: run {
            Logger.w(TAG, "captureScreenImage returned null")
            return null
        }
        return try {
            val bytes = encodePng(bitmap) ?: run {
                Logger.w(TAG, "encodePng failed")
                return null
            }
            val codec = SharedImageCodec.get() ?: run {
                Logger.w(TAG, "SharedImageCodec unavailable")
                return null
            }
            when (val result = withContext(Dispatchers.IO) { codec.ingestBytes(bytes, "image/png") }) {
                is IngestResult.Ok -> {
                    Logger.i(TAG, "screen capture ingested successfully path=${result.image.path}")
                    result.image.path
                }
                is IngestResult.Err -> {
                    Logger.w(TAG, "screen capture ingest failed error=${result.error}")
                    null
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun encodePng(bitmap: Bitmap): ByteArray? = withContext(Dispatchers.Default) {
        try {
            val software = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return@withContext null
            try {
                ByteArrayOutputStream().use { out ->
                    if (!software.compress(Bitmap.CompressFormat.PNG, 100, out)) return@withContext null
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
