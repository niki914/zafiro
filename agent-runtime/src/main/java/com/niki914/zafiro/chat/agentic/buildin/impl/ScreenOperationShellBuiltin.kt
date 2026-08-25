package com.niki914.zafiro.chat.agentic.buildin.impl

import com.niki914.zafiro.chat.agentic.accessibility.AccessibilityController
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolResult
import com.niki914.zafiro.chat.agentic.buildin.ScreenOperationError
import com.niki914.zafiro.chat.agentic.buildin.TextResultBuiltinTool
import com.niki914.zafiro.chat.agentic.buildin.TextToolResult

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
        "Screen interaction via shell input (tap/swipe/keyevent). " +
                "Operations: tap(x,y), long_click(x,y), swipe(start_x,start_y,end_x,end_y,duration), " +
                "key(code). Coordinates are in screen pixels from the most recently returned screen tree. " +
                "Every successful operation returns the updated tree automatically.\n\n" +
                "Key codes: BACK=4, HOME=3, RECENTS=187, NOTIFICATIONS=83, QUICK_SETTINGS=84.\n\n" +
                "wait_mode \"stable\" (default) waits for the UI to settle; \"delay\" waits a fixed " +
                "wait_ms (use for search/refresh).\n\n" +
                "Results use the #!tool-result protocol. Usage rules: see the Phone Use skill."

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
                  "description": "X coordinate in screen pixels (from the latest screen tree). Required for tap, long_click."
                },
                "y": {
                  "type": "number",
                  "description": "Y coordinate in screen pixels (from the latest screen tree). Required for tap, long_click."
                },
                "start_x": {
                  "type": "number",
                  "description": "Swipe start X. Required for swipe."
                },
                "start_y": {
                  "type": "number",
                  "description": "Swipe start Y. Required for swipe."
                },
                "end_x": {
                  "type": "number",
                  "description": "Swipe end X. Required for swipe."
                },
                "end_y": {
                  "type": "number",
                  "description": "Swipe end Y. Required for swipe."
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
                  "description": "\"stable\" (default) waits for the UI to settle, then captures; \"delay\" waits a fixed wait_ms (use for search/refresh with async data)."
                },
                "wait_ms": {
                  "type": "number",
                  "description": "Wait in ms: for \"stable\" the max deadline (default 2000, max 60000); for \"delay\" required, fixed wait (0-60000)."
                }
              },
              "required": ["operation"]
            }
        """
    }
}
