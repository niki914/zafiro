package com.niki914.nexus.agentic.chat.agentic.buildin.impl

import com.niki914.nexus.agentic.chat.agentic.accessibility.AccessibilityController
import com.niki914.nexus.agentic.chat.agentic.accessibility.NodeAction
import com.niki914.nexus.agentic.chat.agentic.accessibility.ScreenSnapshot
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolResult
import com.niki914.nexus.agentic.chat.agentic.buildin.ScreenOperationError
import com.niki914.nexus.agentic.chat.agentic.buildin.TextResultBuiltinTool
import com.niki914.nexus.agentic.chat.agentic.buildin.TextToolResult
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
        "Screen interaction via accessibility service. " +
                "Operations: read (capture YAML tree), tap, long_click, scroll_forward, " +
                "scroll_backward, set_text, search. Target nodes by constructing a token " +
                "from the snapshot version (YAML header) and the node's index (i field), " +
                "joined with underscore: \"{version}_{i}\" (e.g. version \"a3f2c91e7b40\" + " +
                "node {i: 42, ...} → token \"a3f2c91e7b40_42\"). " +
                "Every successful write op auto-captures the updated tree — " +
                "no separate read needed.\n\n" +
                "YAML fields: version=snapshot_version (header), " +
                "i=node_index (assemble token as {version}_{i} when calling back), " +
                "t=semantic_type(button/input/text/image/list/list_item/switch/checkbox/tab/chip/toolbar/dialog/container), " +
                "b=bounds[left,top,right,bottom], pos=3x3_grid_position, txt=display_text, h=content_description, " +
                "tap=clickable, hold=long_clickable, edit=editable, scroll=scrollable, " +
                "checked=checked_state, ch=children, more=off_screen_children_summaries.\n\n" +
                "search: case-insensitive keyword match on txt/h. " +
                "keywords: [\"term1\", \"term2\"] (required, JSON string array). " +
                "match_mode: \"any\" (default) | \"all\". " +
                "limit: max results (default 10). " +
                "Returns matched nodes with index + version header.\n\n" +
                "If read returns root-only or empty tree: app likely uses non-native UI " +
                "(Flutter/Unity/WebView) — stop, do not retry.\n\n" +
                "wait_mode (default \"stable\"): \"stable\" detects when the UI actually settles " +
                "(event idle + tree hash) and returns early — use for taps, scrolls, text input. " +
                "\"delay\" does a blind fixed wait — use for search/refresh where data arrives " +
                "asynchronously and the UI may appear stable before results load. " +
                "Must be \"stable\" or \"delay\".\n" +
                "wait_ms: for \"stable\" the max deadline (default 2000, max 60000); " +
                "for \"delay\" required (no default), the fixed blind-wait duration.\n\n" +
                "Every read, search, and successful write operation produces a fresh version. " +
                "Assemble tokens from the most recently returned result only.\n\n" +
                "Every result uses the #!tool-result protocol " +
                "(#!status, #!code, #!message, then payload). " +
                "See the Phone Use skill for failure recovery rules."

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
        // T2a 迁移：原 kai LocalToolConfig DSL（string/number 声明）转录为 JSON Schema，
        // 字段描述文本一字未改。
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
