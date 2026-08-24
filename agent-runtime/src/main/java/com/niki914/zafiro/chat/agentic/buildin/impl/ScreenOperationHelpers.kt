package com.niki914.zafiro.chat.agentic.buildin.impl

import com.niki914.zafiro.chat.agentic.accessibility.ScreenSnapshot
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolResult
import com.niki914.zafiro.chat.agentic.buildin.ScreenOperationError
import com.niki914.zafiro.chat.agentic.buildin.TextToolResult

/**
 * Assembles a [TextToolResult] after a node/shell action fails and a screen capture
 * is attempted.
 *
 * If the capture succeeds, the result includes the fresh tree as payload and a
 * retry hint that varies by error code:
 * - [ScreenOperationError.SHELL_TIMEOUT], [ScreenOperationError.SHELL_SESSION_LOST]:
 *   the action may have partially executed; the hint warns the LLM to inspect the
 *   tree and NOT blindly retry.
 * - All other codes: the action was definitely not executed; the hint tells the LLM
 *   to inspect the included tree and retry.
 *
 * If the capture also fails, [failureWithCaptureError] combines both error messages.
 */
internal fun assembleActionResult(
    actionResult: BuiltinToolResult,
    captureResult: Result<ScreenSnapshot>,
): TextToolResult {
    return captureResult.fold(
        onSuccess = { snapshot ->
            val retryHint = when (actionResult.code) {
                ScreenOperationError.SHELL_TIMEOUT.code,
                ScreenOperationError.SHELL_SESSION_LOST.code ->
                    "inspect the tree to determine whether the action took effect. " +
                        "Do NOT retry the same action unless the screen confirms " +
                        "it did not execute."
                else ->
                    "inspect the included tree and retry without calling read."
            }
            TextToolResult.failure(
                code = actionResult.code,
                message = "The action failed: ${actionResult.message}. " +
                    "A fresh screen tree is included below — $retryHint",
                payload = snapshot.yaml,
            )
        },
        onFailure = { e ->
            failureWithCaptureError(actionResult.code, actionResult.message, e)
        },
    )
}

/**
 * Creates a [TextToolResult] failure when both the action and the subsequent
 * screen capture have failed. The original action [code] is preserved, and the
 * [message] combines the action's error with the capture error.
 */
internal fun failureWithCaptureError(
    code: String,
    actionMessage: String,
    captureError: Throwable,
): TextToolResult = TextToolResult.failure(
    code = code,
    message = buildString {
        append(actionMessage)
        append(". Latest screen tree capture failed: ")
        append(captureError.message ?: "unknown error")
    },
)
