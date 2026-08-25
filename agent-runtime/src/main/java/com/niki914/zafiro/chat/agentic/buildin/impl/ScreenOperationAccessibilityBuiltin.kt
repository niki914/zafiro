package com.niki914.zafiro.chat.agentic.buildin.impl

import com.niki914.zafiro.chat.agentic.accessibility.AccessibilityController
import com.niki914.zafiro.chat.agentic.accessibility.NodeAction
import com.niki914.zafiro.chat.agentic.accessibility.ScreenSnapshot
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolResult
import com.niki914.zafiro.chat.agentic.buildin.ScreenOperationError
import com.niki914.zafiro.chat.agentic.buildin.TextResultBuiltinTool
import com.niki914.zafiro.chat.agentic.buildin.TextToolResult
import kotlinx.coroutines.delay

/**
 * TextResultBuiltinTool for accessibility-service-based screen interaction.
 *
 * Supports read, tap, long_click, scroll_forward, scroll_backward, set_text
 * (all node-based via token), and search. Every successful write operation auto-captures
 * the updated screen tree after execution.
 *
 * Every result uses the #!tool-result protocol.
 * See the Phone Use skill for failure recovery rules.
 */
class ScreenOperationAccessibilityBuiltin : TextResultBuiltinTool() {
    override val name = "screen_operation_accessibility"
    override val defaultEnabled = true
    override val description: String =
        "Read and interact with the Android screen through the accessibility service. " +
                "Operations: read (capture YAML UI tree), tap, long_click, scroll_forward, " +
                "scroll_backward, set_text, search. " +
                "Target a node by token \"{version}_{i}\" — snapshot version from the YAML header + " +
                "node index from the i field (e.g. version \"a3f2c91e7b40\" + node {i: 42} → " +
                "token \"a3f2c91e7b40_42\"). Every successful write op returns the updated tree. " +
                "Assemble tokens from the most recently returned result only.\n\n" +
                "Node fields: i=index, t=semantic_type (button/input/...), b=bounds[left,top,right,bottom], " +
                "pos=3x3_grid_position, txt=display_text, h=content_description, tap=clickable, " +
                "hold=long_clickable, edit=editable, scroll=scrollable, checked=checked_state, " +
                "ch=children, more=off_screen_children_summaries.\n\n" +
                "search: keywords (required, JSON string array), match_mode \"any\" (default) | \"all\", " +
                "limit (default 10). Returns matched nodes with index and version header.\n\n" +
                "wait_mode \"stable\" (default) waits for the UI to settle and returns early; " +
                "\"delay\" waits a fixed wait_ms — use for search/refresh where data arrives asynchronously.\n\n" +
                "If read returns a root-only or empty tree, the app likely uses non-native UI " +
                "(Flutter/Unity/WebView) — report this to the user without retrying.\n\n" +
                "Results use the #!tool-result protocol. Usage rules and failure recovery: see the Phone Use skill."

    override val inputSchemaJson: String? get() = SCREEN_ACCESSIBILITY_SCHEMA

    override suspend fun invokeText(request: BuiltinToolRequest): TextToolResult {
        AccessibilityController.ensurePointerShown()

        val args = parseArguments(request.argumentsJson).getOrElse { error ->
            val msg = error.message ?: "Invalid arguments JSON"
            val code = if (msg.startsWith("Unknown operation")) ScreenOperationError.INVALID_OPERATION.code else ScreenOperationError.INVALID_ARGUMENTS_JSON.code
            return TextToolResult.failure(code, msg)
        }

        return when (val op = args.operation) {
            is ScreenOp.Read -> {
                val capture = captureAfterOptionalWait(args)
                capture.fold(
                    onSuccess = { TextToolResult.success(it.yaml) },
                    onFailure = { e ->
                        TextToolResult.failure(
                            ScreenOperationError.SERVICE_UNAVAILABLE.code,
                            // 给 LLM 的工具错误 fallback（模型消费的英文契约文本），非 UI 本地化文案，保持英文
                            e.message ?: "Service unavailable",
                        )
                    },
                )
            }

            is ScreenOp.Tap -> executeNodeActionAndCapture(
                op.token, NodeAction.CLICK, null, args.waitMode, args.waitMs,
            )

            is ScreenOp.LongClick -> executeNodeActionAndCapture(
                op.token, NodeAction.LONG_CLICK, null, args.waitMode, args.waitMs,
            )

            is ScreenOp.ScrollForward -> executeNodeActionAndCapture(
                op.token, NodeAction.SCROLL_FORWARD, null, args.waitMode, args.waitMs,
            )

            is ScreenOp.ScrollBackward -> executeNodeActionAndCapture(
                op.token, NodeAction.SCROLL_BACKWARD, null, args.waitMode, args.waitMs,
            )

            is ScreenOp.SetText -> executeNodeActionAndCapture(
                op.token, NodeAction.SET_TEXT, op.text, args.waitMode, args.waitMs,
            )

            is ScreenOp.Search -> {
                waitBeforeSearch(args)
                AccessibilityController.searchNodes(op.keywords, op.matchMode, op.limit)
                    .fold(
                        onSuccess = { TextToolResult.success(it) },
                        onFailure = { e ->
                            TextToolResult.failure(
                                ScreenOperationError.SEARCH_FAILED.code,
                                e.message ?: "Search failed",
                            )
                        },
                    )
            }

            else -> TextToolResult.failure(
                code = ScreenOperationError.INVALID_OPERATION.code,
                message = "Operation '${op::class.simpleName}' not supported by " +
                    "screen_operation_accessibility. Use screen_operation_shell for " +
                    "shell-based operations.",
            )
        }
    }

    /**
     * Executes a node action, then captures the updated screen according to [waitMode].
     *
     * When the action itself fails, the screen is captured to provide a fresh tree
     * for the LLM to retry with, instead of returning a bare error.
     *
     * Returns a [TextToolResult] — success with the YAML tree, or failure with
     * an optional payload.
     */
    private suspend fun executeNodeActionAndCapture(
        token: String,
        action: NodeAction,
        text: String?,
        waitMode: String,
        waitMs: Long,
    ): TextToolResult {
        val actionResult = AccessibilityController.executeNodeAction(token, action, text)
        if (!actionResult.ok) {
            val captureResult = AccessibilityController.captureScreen()
            return assembleActionResult(actionResult, captureResult)
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
                    message = "The action may have succeeded, but the updated screen tree " +
                        "could not be captured. Read the screen before deciding whether to " +
                        "retry the action.",
                )
            },
        )
    }

    /**
     * Captures the screen, optionally waiting first when [ScreenOpArgs.hasExplicitWaitMode]
     * is true. Without an explicit wait request, captures immediately (legacy read behavior).
     */
    private suspend fun captureAfterOptionalWait(args: ScreenOpArgs): Result<ScreenSnapshot> {
        if (!args.hasExplicitWaitMode) return AccessibilityController.captureScreen()
        return if (args.waitMode == "delay") {
            AccessibilityController.captureScreenAfterDelay(args.waitMs)
        } else {
            AccessibilityController.waitForStable(args.waitMs)
        }
    }

    /** Waits before a search when the agent explicitly requested it. */
    private suspend fun waitBeforeSearch(args: ScreenOpArgs) {
        if (!args.hasExplicitWaitMode) return
        if (args.waitMode == "delay") {
            delay(args.waitMs)
        } else {
            AccessibilityController.waitForStable(args.waitMs)
        }
    }

    private companion object {
        private val SCREEN_ACCESSIBILITY_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "operation": {
                  "type": "string",
                  "description": "Which operation: read, tap, long_click, scroll_forward, scroll_backward, set_text, search."
                },
                "token": {
                  "type": "string",
                  "description": "Target node token, assembled as {version}_{i} — snapshot version from YAML header + underscore + node index from the i field. Required for tap, long_click, scroll_forward, scroll_backward, set_text."
                },
                "text": {
                  "type": "string",
                  "description": "Text to type into the field. Required for set_text."
                },
                "match_mode": {
                  "type": "string",
                  "description": "Search match mode: \"any\" (default) to match any keyword, \"all\" to require all keywords."
                },
                "limit": {
                  "type": "number",
                  "description": "Max search results to return, default 10."
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
