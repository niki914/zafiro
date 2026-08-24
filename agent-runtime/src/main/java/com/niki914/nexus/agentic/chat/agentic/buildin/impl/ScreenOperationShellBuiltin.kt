package com.niki914.nexus.agentic.chat.agentic.buildin.impl

import com.niki914.nexus.agentic.chat.agentic.accessibility.AccessibilityController
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolResult
import com.niki914.nexus.agentic.chat.agentic.buildin.ScreenOperationError
import com.niki914.nexus.agentic.chat.agentic.buildin.TextResultBuiltinTool
import com.niki914.nexus.agentic.chat.agentic.buildin.TextToolResult

/**
 * TextResultBuiltinTool for shell-based screen interaction.
 *
 * FALLBACK method — prefer [ScreenOperationAccessibilityBuiltin] when possible.
 * Supports tap, long_click, swipe, key (all coordinate-based). Coordinates MUST
 * come from the most recently returned screen tree. Every successful write operation
 * auto-captures the updated screen tree via accessibility after execution.
 *
 * Every result uses the #!tool-result protocol.
 * See the Phone Use skill for failure recovery rules.
 */
class ScreenOperationShellBuiltin : TextResultBuiltinTool() {
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
                "wait_ms (default 2000): deadline for stable, required for delay.\n\n" +
                "Every result uses the #!tool-result protocol " +
                "(#!status, #!code, #!message, then payload). " +
                "See the Phone Use skill for failure recovery rules."

    override val inputSchemaJson: String? get() = SCREEN_SHELL_SCHEMA

    override suspend fun invokeText(request: BuiltinToolRequest): TextToolResult {
        AccessibilityController.ensurePointerShown()

        val args = parseArguments(request.argumentsJson).getOrElse { error ->
            val msg = error.message ?: "Invalid arguments JSON"
            val code = if (msg.startsWith("Unknown operation")) ScreenOperationError.INVALID_OPERATION.code else ScreenOperationError.INVALID_ARGUMENTS_JSON.code
            return TextToolResult.failure(code, msg)
        }

        return when (val op = args.operation) {
            is ScreenOp.ShellTap -> executeShellAndCapture(args.waitMode, args.waitMs) {
                AccessibilityController.executeShellTap(op.x, op.y)
            }

            is ScreenOp.ShellLongClick -> executeShellAndCapture(args.waitMode, args.waitMs) {
                AccessibilityController.executeShellLongClick(op.x, op.y)
            }

            is ScreenOp.ShellSwipe -> executeShellAndCapture(args.waitMode, args.waitMs) {
                AccessibilityController.executeShellSwipe(
                    op.startX, op.startY, op.endX, op.endY, op.duration,
                )
            }

            is ScreenOp.ShellKey -> executeShellAndCapture(args.waitMode, args.waitMs) {
                AccessibilityController.executeKeyEvent(op.code)
            }

            else -> TextToolResult.failure(
                code = ScreenOperationError.INVALID_OPERATION.code,
                message = "Operation '${op::class.simpleName}' not supported by " +
                    "screen_operation_shell. Use screen_operation_accessibility for " +
                    "node-based operations.",
            )
        }
    }

    /**
     * Executes a shell operation, then captures the updated screen according to [waitMode].
     *
     * When the shell operation itself fails, the screen is captured to provide a fresh
     * tree for the LLM to retry with. For [SHELL_TIMEOUT] and [SHELL_SESSION_LOST] codes
     * the action may have partially executed, so the message notes this uncertainty.
     *
     * Returns a [TextToolResult] — success with the YAML tree, or failure with
     * an optional payload.
     */
    private suspend fun executeShellAndCapture(
        waitMode: String,
        waitMs: Long,
        executor: suspend () -> BuiltinToolResult,
    ): TextToolResult {
        val result = executor()
        if (!result.ok) {
            val captureResult = AccessibilityController.captureScreen()
            val enhanced = when (result.code) {
                ScreenOperationError.SHELL_TIMEOUT.code,
                ScreenOperationError.SHELL_SESSION_LOST.code -> {
                    result.copy(
                        message = "The shell command may have partially executed before the " +
                            "timeout/session loss. Inspect the included tree to determine the " +
                            "actual state before deciding whether to retry.",
                    )
                }
                else -> result
            }
            return assembleActionResult(enhanced, captureResult)
        }
        val capture = if (waitMode == "delay") {
            AccessibilityController.captureScreenAfterDelay(waitMs)
        } else {
            AccessibilityController.waitForStable(waitMs)
        }
        return capture.fold(
            onSuccess = { snapshot -> TextToolResult.success(snapshot.yaml) },
            onFailure = { e ->
                TextToolResult.failure(
                    code = ScreenOperationError.CAPTURE_FAILED_AFTER_ACTION.code,
                    message = "The shell action may have succeeded, but the updated screen " +
                        "tree could not be captured. Read the screen before deciding whether " +
                        "to retry the action.",
                )
            },
        )
    }

    private companion object {
        // T2a 迁移：原 kai LocalToolConfig DSL（string/number 声明）转录为 JSON Schema，
        // 字段描述文本一字未改。
        private val SCREEN_SHELL_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "operation": {
                  "type": "string",
                  "description": "Which operation: tap, long_click, swipe, key."
                },
                "x": {
                  "type": "number",
                  "description": "X coordinate in screen pixels. Required for tap, long_click."
                },
                "y": {
                  "type": "number",
                  "description": "Y coordinate in screen pixels. Required for tap, long_click."
                },
                "start_x": {
                  "type": "number",
                  "description": "Swipe start X coordinate. Required for swipe."
                },
                "start_y": {
                  "type": "number",
                  "description": "Swipe start Y coordinate. Required for swipe."
                },
                "end_x": {
                  "type": "number",
                  "description": "Swipe end X coordinate. Required for swipe."
                },
                "end_y": {
                  "type": "number",
                  "description": "Swipe end Y coordinate. Required for swipe."
                },
                "duration": {
                  "type": "number",
                  "description": "Swipe duration in ms, default 300."
                },
                "code": {
                  "type": "number",
                  "description": "Android key code: BACK=4, HOME=3, RECENTS=187, NOTIFICATIONS=83, QUICK_SETTINGS=84."
                },
                "wait_mode": {
                  "type": "string",
                  "description": "\"stable\" (default): detect UI stability before capture, returns early if settled. \"delay\": blind fixed wait — use for search/refresh. Must be \"stable\" or \"delay\"."
                },
                "wait_ms": {
                  "type": "number",
                  "description": "Wait duration in ms. Stable mode: max deadline (default 2000, max 60000). Delay mode: required, fixed sleep (0-60000)."
                }
              },
              "required": ["operation"]
            }
        """
    }
}
