package com.niki914.zafiro.chat.agentic.buildin.impl

import com.niki914.logging.Logger
import com.niki914.xposed.api.util.ContextProvider
import com.niki914.zafiro.chat.agentic.buildin.BuiltinTool
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolResult
import com.niki914.zafiro.chat.agentic.image.IngestError
import com.niki914.zafiro.chat.agentic.image.IngestResult
import com.niki914.zafiro.chat.agentic.shell.TerminalCommandOutcome
import com.niki914.zafiro.chat.agentic.shell.TerminalSessionPool
import com.niki914.zafiro.chat.agentic.SharedImageCodec
import com.niki914.zafiro.chat.agentic.toImageToolResult
import com.niki914.zafiro.chat.agentic.toToolError
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * screenshot 工具：截取整个设备屏幕，ingest 后返回图片引用。
 * 无参数；截屏目标路径由工具自行生成（AI 不感知路径来源）。
 *
 * 截屏通道（MVP）：libterm root → shizuku 逐个尝试，跑 `screencap -p`。
 * 无障碍兜底通道留缝（返回 unsupported），后续作为 root 不可用时的 fallback。
 * 结果契约与 view_image 完全一致：data.image = { path, mime_type, width, height, bytes }。
 */
class ScreenshotBuiltin : BuiltinTool() {
    override val name: String = "screenshot"
    override val description: String = """
Capture the entire device screen as an image so the model can see it.
Use when the user asks about what is currently on screen, or to verify the visual result of an action.
Returns an image reference (path, dimensions, size) on success, or an error code if no privileged
shell (root/shizuku) is available or the capture failed.
    """.trimIndent()
    override val defaultEnabled: Boolean = true
    override val inputSchemaJson: String? = SCHEMA

    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
        val codec = SharedImageCodec.get() ?: return BuiltinToolResult.failure(
            code = "CODEC_UNAVAILABLE",
            message = "Image codec is not available.",
            hint = "Application context is not initialized yet. Retry later."
        )

        val rawPath = captureRawScreenshot()
            ?: return BuiltinToolResult.failure(
                code = "PERMISSION_DENIED",
                message = "Screen capture requires a privileged shell (root or shizuku); neither is available.",
                hint = "Grant root or start shizuku, then retry."
            )

        try {
            return when (val result = codec.ingestFile(rawPath)) {
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
        } finally {
            // 原始 PNG 只服务于 ingest，转码 JPEG 落盘后即可删除
            runCatching { File(rawPath).delete() }
        }
    }

    /**
     * 通过 libterm 截屏到 app 私有目录，返回原始文件绝对路径；失败返回 null。
     * root → shizuku 逐个尝试（同 AccessibilityController 的降级模式）；
     * exitCode == 0 且文件真实存在才算成功——denied 的 su 可能返回非 root shell，
     * 仅凭 TerminalCommandOutcome.Success 不可信。
     */
    private suspend fun captureRawScreenshot(): String? {
        val context = try {
            ContextProvider.await().applicationContext
        } catch (e: Exception) {
            return null
        } ?: return null
        val cacheDir = File(context.cacheDir, "screenshot").apply { mkdirs() }
        val rawFile = File(cacheDir, "capture_${System.currentTimeMillis()}.png")

        for (identity in listOf("root", "shizuku")) {
            val outcome = TerminalSessionPool.openAndExecute(
                identity = identity,
                cwd = null,
                command = "screencap -p ${rawFile.absolutePath}",
                timeoutMs = CAPTURE_TIMEOUT_MS,
            )
            val session = (outcome as? TerminalCommandOutcome.Success)?.session
                ?: (outcome as? TerminalCommandOutcome.Timeout)?.session
            if (session != null) {
                runCatching { TerminalSessionPool.close(session) }
            }
            if (outcome is TerminalCommandOutcome.Success && outcome.result.exitCode == 0) {
                // cat > file 重定向场景下 shell 退出码可能不可靠，以文件存在为准
                if (rawFile.exists() && rawFile.length() > 0) {
                    return rawFile.absolutePath
                }
            }
            Logger.w(
                LOG_TAG,
                "screencap failed identity=$identity outcome=${outcome::class.simpleName}"
            )
        }
        // 无障碍兜底通道留缝：当前未实现，root/shizuku 均不可用时直接失败
        return null
    }

    private companion object {
        private const val LOG_TAG = "niki914_nexus_ScreenshotBuiltin"
        private const val CAPTURE_TIMEOUT_MS = 15_000L

        private val SCHEMA = """
{
  "type": "object",
  "properties": {},
  "required": []
}
        """.trimIndent()
    }
}
