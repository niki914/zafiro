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
 * come from the most recently returned screen tree. Every successful write operation auto-captures the updated
 * screen tree via accessibility after execution.
 */
class ScreenOperationShellBuiltin : RawBuiltinTool() {
    override val name = "screen_operation_shell"
    override val defaultEnabled = true
    override val description: String =
        "Screen interaction via shell (input tap/swipe/keyevent). FALLBACK — prefer " +
                "screen_operation_accessibility. Operations: tap(x,y), long_click(x,y), " +
                "swipe(start_x,start_y,end_x,end_y,duration), key(code). All coordinate-based. " +
                "Coordinates MUST come from the most recently returned screen tree — never " +
                "hallucinate. Every successful write op auto-captures the updated tree.\n\n" +
                "Key codes: BACK=4, HOME=3, RECENTS=187, NOTIFICATIONS=83, QUICK_SETTINGS=84.\n\n" +
                "wait_mode (default \"stable\"): \"stable\" auto-detects UI stability before capture. " +
                "\"delay\" does a blind fixed wait — use for search/refresh. " +
                "wait_ms (default 5000): deadline for stable, fixed sleep for delay."

    override fun configure(config: LocalToolConfig) {
        config.description = description
        config.string("operation") {
            description = "Which operation: tap, long_click, swipe, key."
            required = true
        }
        config.number("x") {
            description = "X coordinate in screen pixels. Required for tap, long_click."
            required = false
        }
        config.number("y") {
            description = "Y coordinate in screen pixels. Required for tap, long_click."
            required = false
        }
        config.number("start_x") {
            description = "Swipe start X coordinate. Required for swipe."
            required = false
        }
        config.number("start_y") {
            description = "Swipe start Y coordinate. Required for swipe."
            required = false
        }
        config.number("end_x") {
            description = "Swipe end X coordinate. Required for swipe."
            required = false
        }
        config.number("end_y") {
            description = "Swipe end Y coordinate. Required for swipe."
            required = false
        }
        config.number("duration") {
            description = "Swipe duration in ms, default 300."
            required = false
        }
        config.number("code") {
            description = "Android key code: BACK=4, HOME=3, RECENTS=187, NOTIFICATIONS=83, QUICK_SETTINGS=84."
            required = false
        }
        config.string("wait_mode") {
            description = "\"stable\" (default): detect UI stability before capture, returns early if settled. \"delay\": blind fixed wait — use for search/refresh."
            required = false
        }
        config.number("wait_ms") {
            description = "Wait duration in ms, default 5000. For stable mode: max deadline. For delay mode: fixed sleep."
            required = false
        }
    }

    override suspend fun invokeRaw(request: BuiltinToolRequest): String {
        AccessibilityController.ensurePointerShown()

        val args = parseArguments(request.argumentsJson).getOrElse { error ->
            val msg = error.message ?: "Invalid arguments JSON"
            val code = if (msg.startsWith("Unknown operation")) "INVALID_OPERATION" else "INVALID_ARGUMENTS_JSON"
            return errorJson(code, msg)
        }

        return try {
            when (val op = args.operation) {
                is ScreenOp.ShellTap -> executeShellAndCapture(args.waitMode, args.waitMs) {
                    AccessibilityController.executeShellTap(op.x, op.y)
                }

                is ScreenOp.ShellLongClick -> executeShellAndCapture(args.waitMode, args.waitMs) {
                    AccessibilityController.executeShellLongClick(op.x, op.y)
                }

                is ScreenOp.ShellSwipe -> executeShellAndCapture(args.waitMode, args.waitMs) {
                    AccessibilityController.executeShellSwipe(
                        op.startX, op.startY, op.endX, op.endY, op.duration
                    )
                }

                is ScreenOp.ShellKey -> executeShellAndCapture(args.waitMode, args.waitMs) {
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
     * Executes a shell operation, then captures the updated screen according to [waitMode].
     *
     * Returns the YAML representation on success, or an error JSON string on failure.
     */
    private suspend fun executeShellAndCapture(
        waitMode: String,
        waitMs: Long,
        executor: suspend () -> BuiltinToolResult,
    ): String {
        val result = executor()
        if (!result.ok) {
            return errorJson(result.code, result.message)
        }
        val capture = if (waitMode == "delay") {
            AccessibilityController.captureScreenAfterDelay(waitMs)
        } else {
            AccessibilityController.waitForStable(waitMs)
        }
        return capture.fold(
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
