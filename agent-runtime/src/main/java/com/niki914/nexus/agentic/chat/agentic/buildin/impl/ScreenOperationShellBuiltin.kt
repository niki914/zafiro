package com.niki914.nexus.agentic.chat.agentic.buildin.impl

import com.niki914.nexus.agentic.chat.agentic.accessibility.AccessibilityController
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolResult
import com.niki914.nexus.agentic.chat.agentic.buildin.RawBuiltinTool
import com.niki914.nexus.agentic.chat.agentic.buildin.ScreenOperationError
import com.niki914.s3ss10n.LocalToolConfig
import kotlinx.coroutines.CancellationException

/**
 * RawBuiltinTool for shell-based screen interaction.
 *
 * FALLBACK method — prefer [ScreenOperationAccessibilityBuiltin] when possible.
 * Supports tap, long_click, swipe, key (all coordinate-based). Coordinates MUST
 * come from a prior screen read. Every write operation auto-captures the updated
 * screen tree via accessibility after execution.
 */
class ScreenOperationShellBuiltin : RawBuiltinTool() {
    override val name = "screen_operation_shell"
    override val defaultEnabled = true
    override val description: String =
        "Screen interaction via shell (input tap/swipe/keyevent). FALLBACK method — prefer " +
                "screen_operation_accessibility when possible. Operations: tap, long_click, swipe, " +
                "key (all coordinate-based). Coordinates MUST come from a prior screen read " +
                "(never hallucinate). Every write operation auto-captures the updated screen tree " +
                "via accessibility after execution. Pass delay_ms (default 1000) to control " +
                "post-action wait before capture."

    override fun configure(config: LocalToolConfig) {
        config.description = description
    }

    override suspend fun invokeRaw(request: BuiltinToolRequest): String {
        val args = parseArguments(request.argumentsJson).getOrElse { error ->
            return errorJson("INVALID_ARGUMENTS_JSON", error.message ?: "Invalid arguments JSON")
        }

        return try {
            when (val op = args.operation) {
                is ScreenOp.ShellTap -> executeShellAndCapture(args.delayMs) {
                    AccessibilityController.executeShellTap(op.x, op.y)
                }

                is ScreenOp.ShellLongClick -> executeShellAndCapture(args.delayMs) {
                    AccessibilityController.executeShellLongClick(op.x, op.y)
                }

                is ScreenOp.ShellSwipe -> executeShellAndCapture(args.delayMs) {
                    AccessibilityController.executeShellSwipe(
                        op.startX, op.startY, op.endX, op.endY, op.duration
                    )
                }

                is ScreenOp.ShellKey -> executeShellAndCapture(args.delayMs) {
                    AccessibilityController.executeKeyEvent(op.code)
                }

                else -> errorJson(
                    "INVALID_OPERATION",
                    "Operation '${op::class.simpleName}' not supported by " +
                            "screen_operation_shell. Use screen_operation_accessibility for " +
                            "node-based operations."
                )
            }
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            errorJson("INTERNAL_ERROR", throwable.message ?: "Unknown internal error")
        }
    }

    /**
     * Executes a shell operation, then captures the updated screen after a delay.
     *
     * Returns the YAML representation on success, or an error JSON string on failure.
     */
    private suspend fun executeShellAndCapture(
        delayMs: Long,
        executor: suspend () -> BuiltinToolResult,
    ): String {
        val result = executor()
        if (!result.ok) {
            return errorJson(result.code, result.message)
        }
        return AccessibilityController.captureScreenAfterDelay(delayMs)
            .fold(
                onSuccess = { it.yaml },
                onFailure = { e ->
                    errorJson("SERVICE_UNAVAILABLE", e.message ?: "Service unavailable")
                },
            )
    }

    private fun errorJson(code: String, message: String): String {
        return ScreenOperationError.errorJson(code, message)
    }
}
