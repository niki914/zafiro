package com.niki914.zafiro.chat.agentic.buildin.impl

import android.graphics.Bitmap
import android.os.Build
import com.niki914.zafiro.chat.agentic.SharedImageCodec
import com.niki914.zafiro.chat.agentic.accessibility.AccessibilityController
import com.niki914.zafiro.chat.agentic.buildin.BuiltinTool
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolResult
import com.niki914.zafiro.chat.agentic.image.IngestResult
import com.niki914.zafiro.chat.agentic.toImageToolResult
import com.niki914.zafiro.chat.agentic.toToolError
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * screenshot 工具：截取整个设备屏幕，ingest 后返回图片引用。
 * 无参数；截屏目标路径由工具自行生成（AI 不感知路径来源）。
 *
 * 截屏通道只有一条：官方无障碍截屏 API（[AccessibilityController.captureScreenImage]），不需要 root。
 * root / Shizuku 的角色只是让 [AccessibilityController.ensureAccessibility] 能静默把无障碍服务打开
 * （PermissionManager 的默认链），不再有一条 shell `screencap` 通道。安全窗口（DRM / 密码输入）
 * 会被系统拒绝，这是官方 API 的边界——需要绕过时用 terminal 工具自己截图。
 * 结果契约与 view_image 完全一致：data.image = { path, mime_type, width, height, bytes }。
 */
class ScreenshotBuiltin : BuiltinTool() {
    override val name: String = "screenshot"
    override val description: String = """
Capture the entire device screen as an image so the model can see it.
Use when the user asks about what is currently on screen, or to verify the visual result of an action.
Requires Android 11+ and the Zafiro accessibility service; on older Android versions use the terminal
shell tool with a privileged shell instead. Returns an error code when unavailable or the capture failed.
Returns an image reference (path, dimensions, size) on success.
    """.trimIndent()
    override val defaultEnabled: Boolean = true
    override val inputSchemaJson: String? = SCHEMA

    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // API 30 之前系统没有无障碍截屏 API，只有特权 shell 的 `screencap` 能拍：
            // 直接把模型指向 terminal 工具，别让它在这个工具上反复重试。
            return BuiltinToolResult.failure(
                code = "SCREENSHOT_UNSUPPORTED",
                message = "This device runs Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}); " +
                        "the accessibility screenshot API requires Android 11 (API 30) or newer.",
                hint = "Do not retry this tool. Use the 'terminal' tool instead: it needs a privileged shell " +
                        "(root or shizuku) and can run 'screencap -p <file>', then read that file with 'view_image'."
            )
        }

        val codec = SharedImageCodec.get() ?: return BuiltinToolResult.failure(
            code = "CODEC_UNAVAILABLE",
            message = "Image codec is not available.",
            hint = "Application context is not initialized yet. Retry later."
        )

        AccessibilityController.ensureAccessibility().onFailure { error ->
            return BuiltinToolResult.failure(
                code = "ACCESSIBILITY_UNAVAILABLE",
                message = error.message ?: "Screen capture requires the Zafiro accessibility service.",
                hint = "Tell the user to enable 'Zafiro' in Settings > Accessibility, then retry."
            )
        }

        val bitmap = AccessibilityController.captureScreenImage().getOrElse { error ->
            return BuiltinToolResult.failure(
                code = "SCREENSHOT_FAILED",
                message = error.message ?: "Screen capture failed.",
                hint = "Secure or protected content cannot be captured. Retry when a normal app is on screen."
            )
        }

        val bytes = encodePng(bitmap) ?: return BuiltinToolResult.failure(
            code = "SCREENSHOT_FAILED",
            message = "The captured frame could not be encoded.",
            hint = "Retry."
        )

        return when (val result = withContext(Dispatchers.IO) { codec.ingestBytes(bytes, "image/png") }) {
            is IngestResult.Ok -> result.toImageToolResult(
                message = "Screenshot captured: ${result.image.path}",
            )

            is IngestResult.Err -> {
                val (code, message) = result.error.toToolError()
                BuiltinToolResult.failure(
                    code = code,
                    message = message,
                    hint = "The screen was captured but the image could not be processed. Retry."
                )
            }
        }
    }

    /**
     * 官方截屏给出的是 HARDWARE 位图（像素不可直接读）：先拷成软件位图，再无损失编码为 PNG，
     * 交给统一 ingest 管线收缩转 JPEG。两位图都在这里回收。
     */
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
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        private val SCHEMA = """
{
  "type": "object",
  "properties": {},
  "required": []
}
        """.trimIndent()
    }
}
