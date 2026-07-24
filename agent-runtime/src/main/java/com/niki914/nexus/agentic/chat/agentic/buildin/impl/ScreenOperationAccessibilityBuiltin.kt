package com.niki914.nexus.agentic.chat.agentic.buildin.impl

import com.niki914.nexus.agentic.chat.agentic.accessibility.AccessibilityController
import com.niki914.nexus.agentic.chat.agentic.accessibility.NodeAction
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.nexus.agentic.chat.agentic.buildin.RawBuiltinTool
import com.niki914.nexus.agentic.chat.agentic.buildin.ScreenOperationError
import com.niki914.s3ss10n.LocalToolConfig
import kotlinx.coroutines.CancellationException

/**
 * RawBuiltinTool for accessibility-service-based screen interaction.
 *
 * Supports read, tap, long_click, scroll_forward, scroll_backward, set_text
 * (all node-based via token), and search. Every write operation auto-captures
 * the updated screen tree after execution.
 */
class ScreenOperationAccessibilityBuiltin : RawBuiltinTool() {
    override val name = "screen_operation_accessibility"
    override val defaultEnabled = true
    override val description: String =
        "Screen interaction via accessibility service. " +
                "Operations: read (capture screen tree as YAML), tap, long_click, scroll_forward, " +
                "scroll_backward, set_text, search (all node-based via token). Every write operation " +
                "auto-captures the updated screen tree after execution. Tokens are ephemeral — always " +
                "use the latest version's tokens. Pass delay_ms (default 1000) to control post-action " +
                "wait before capture."

    override fun configure(config: LocalToolConfig) {
        config.description = description
    }

    override suspend fun invokeRaw(request: BuiltinToolRequest): String {
        val args = parseArguments(request.argumentsJson).getOrElse { error ->
            return errorJson("INVALID_ARGUMENTS_JSON", error.message ?: "Invalid arguments JSON")
        }

        return try {
            when (val op = args.operation) {
                is ScreenOp.Read -> {
                    AccessibilityController.captureScreen()
                        .fold(
                            onSuccess = { it.yaml },
                            onFailure = { e ->
                                errorJson("SERVICE_UNAVAILABLE", e.message ?: "Service unavailable")
                            },
                        )
                }

                is ScreenOp.Tap -> executeNodeActionAndCapture(
                    op.token, NodeAction.CLICK, null, args.delayMs
                )

                is ScreenOp.LongClick -> executeNodeActionAndCapture(
                    op.token, NodeAction.LONG_CLICK, null, args.delayMs
                )

                is ScreenOp.ScrollForward -> executeNodeActionAndCapture(
                    op.token, NodeAction.SCROLL_FORWARD, null, args.delayMs
                )

                is ScreenOp.ScrollBackward -> executeNodeActionAndCapture(
                    op.token, NodeAction.SCROLL_BACKWARD, null, args.delayMs
                )

                is ScreenOp.SetText -> executeNodeActionAndCapture(
                    op.token, NodeAction.SET_TEXT, op.text, args.delayMs
                )

                is ScreenOp.Search -> {
                    AccessibilityController.searchNodes(op.keywords, op.matchMode, op.limit)
                        .fold(
                            onSuccess = { it },
                            onFailure = { e ->
                                errorJson("SEARCH_FAILED", e.message ?: "Search failed")
                            },
                        )
                }

                else -> errorJson(
                    "INVALID_OPERATION",
                    "Operation '${op::class.simpleName}' not supported by " +
                            "screen_operation_accessibility. Use screen_operation_shell for " +
                            "shell-based operations."
                )
            }
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            errorJson("INTERNAL_ERROR", throwable.message ?: "Unknown internal error")
        }
    }

    /**
     * Executes a node action, then captures the updated screen after a delay.
     *
     * Returns the YAML representation on success, or an error JSON string on failure.
     */
    private suspend fun executeNodeActionAndCapture(
        token: String,
        action: NodeAction,
        text: String?,
        delayMs: Long,
    ): String {
        val actionResult = AccessibilityController.executeNodeAction(token, action, text)
        if (!actionResult.ok) {
            return errorJson(actionResult.code, actionResult.message)
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
