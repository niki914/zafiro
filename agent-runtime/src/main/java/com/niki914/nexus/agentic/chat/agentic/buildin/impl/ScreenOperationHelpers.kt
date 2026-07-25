package com.niki914.nexus.agentic.chat.agentic.buildin.impl

import com.niki914.nexus.agentic.chat.agentic.accessibility.ScreenSnapshot
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolResult
import com.niki914.nexus.agentic.chat.agentic.buildin.TextToolResult

/**
 * Assembles a [TextToolResult] after a node/shell action fails and a screen capture
 * is attempted.
 *
 * - If the capture succeeds, the original error message is **overridden** with a
 *   unified hint telling the LLM to retry using the fresh tree's tokens instead
 *   of calling read. The original error [code] is preserved.
 * - If the capture also fails, [failureWithCaptureError] combines both error messages.
 *
 * This is a pure function (no Android dependencies), making it directly testable.
 */
internal fun assembleActionResult(
    actionResult: BuiltinToolResult,
    captureResult: Result<ScreenSnapshot>,
): TextToolResult {
    return captureResult.fold(
        onSuccess = { snapshot ->
            TextToolResult.failure(
                code = actionResult.code,
                message = "The action failed: ${actionResult.message}. " +
                    "A fresh screen tree is included below — " +
                    "retry using its tokens without calling read.",
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
