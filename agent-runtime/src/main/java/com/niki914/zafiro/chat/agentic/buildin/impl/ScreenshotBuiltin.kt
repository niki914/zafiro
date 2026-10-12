package com.niki914.zafiro.chat.agentic.buildin.impl

import android.os.Build
import com.niki914.zafiro.chat.agentic.ScreenCaptureIngestor
import com.niki914.zafiro.chat.agentic.ScreenCaptureIngestor.Stage
import com.niki914.zafiro.chat.agentic.ScreenCaptureIngestor.ToolCaptureResult
import com.niki914.zafiro.chat.agentic.buildin.BuiltinTool
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolResult
import com.niki914.zafiro.chat.agentic.toImageToolResult
import com.niki914.zafiro.chat.agentic.toToolError

/**
 * screenshot 工具：截取整个设备屏幕，ingest 后返回图片引用。
 * 无参数；截屏目标路径由工具自行生成（AI 不感知路径来源）。
 *
 * 截屏通道只有一条：官方无障碍截屏 API（[ScreenCaptureIngestor]），不需要 root。
 * root / Shizuku 的角色只是让无障碍服务能被静默打开（PermissionManager 的默认链）。
 * 安全窗口（DRM / 密码输入）会被系统拒绝，这是官方 API 的边界——需要绕过时用 terminal 工具自己截图。
 * 结果契约与 view_image 完全一致：data.image = { path, mime_type, width, height, bytes }。
 */
class ScreenshotBuiltin : BuiltinTool() {
    override val name: String = "screenshot"
    override val description: String = """
Capture the entire device screen as an image so the model can see it.
Use when the user asks about what is currently on screen, or to verify the visual result of an action.
Requires Android 11+ and the Zafiro accessibility service; on older Android versions use the shell
tool with a privileged identity instead. Returns an error code when unavailable or the capture failed.
Returns an image reference (path, dimensions, size) on success.
    """.trimIndent()
    override val defaultEnabled: Boolean = true
    override val inputSchemaJson: String? = SCHEMA

    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // API 30 之前系统没有无障碍截屏 API，只有特权 shell 的 `screencap` 能拍：
            // 直接把模型指向 shell 工具，别让它在这个工具上反复重试。
            return BuiltinToolResult.failure(
                code = "SCREENSHOT_UNSUPPORTED",
                message = "This device runs Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}); " +
                        "the accessibility screenshot API requires Android 11 (API 30) or newer.",
                hint = "Do not retry this tool. Use the 'shell' tool instead: it needs a privileged identity " +
                        "(root or shizuku) and can run 'screencap -p <file>', then read that file with 'view_image'."
            )
        }

        return when (val result = ScreenCaptureIngestor.captureForTool()) {
            is ToolCaptureResult.Ok -> result.ingest.toImageToolResult(
                message = "Screenshot captured: ${result.ingest.image.path}",
            )

            is ToolCaptureResult.Err -> when (result.stage) {
                Stage.CODEC_UNAVAILABLE -> BuiltinToolResult.failure(
                    code = "CODEC_UNAVAILABLE",
                    message = result.message ?: "Image codec is not available.",
                    hint = "Application context is not initialized yet. Retry later."
                )

                Stage.ACCESSIBILITY_UNAVAILABLE -> BuiltinToolResult.failure(
                    code = "ACCESSIBILITY_UNAVAILABLE",
                    message = result.message
                        ?: "Screen capture requires the Zafiro accessibility service.",
                    hint = "Tell the user to enable 'Zafiro' in Settings > Accessibility, then retry."
                )

                Stage.CAPTURE_FAILED,
                Stage.ENCODE_FAILED,
                    -> BuiltinToolResult.failure(
                    code = "SCREENSHOT_FAILED",
                    message = result.message ?: "Screen capture failed.",
                    hint = "Secure or protected content cannot be captured. Retry when a normal app is on screen."
                )

                Stage.INGEST_FAILED -> error("unreachable: ingest failures come back as IngestErr")
            }

            is ToolCaptureResult.IngestErr -> {
                val (code, message) = result.error.toToolError()
                BuiltinToolResult.failure(
                    code = code,
                    message = message,
                    hint = "The screen was captured but the image could not be processed. Retry."
                )
            }
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
