package com.niki914.nexus.agentic.chat.agentic.buildin.impl

import com.niki914.nexus.agentic.chat.agentic.accessibility.AccessibilityController
import com.niki914.nexus.agentic.chat.agentic.accessibility.NodeAction
import com.niki914.nexus.agentic.chat.agentic.accessibility.ScreenSnapshot
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.nexus.agentic.chat.agentic.buildin.RawBuiltinTool
import com.niki914.nexus.agentic.chat.agentic.buildin.ScreenOperationError
import com.niki914.s3ss10n.LocalToolConfig
import kotlinx.coroutines.CancellationException

/**
 * RawBuiltinTool for accessibility-service-based screen interaction.
 *
 * Supports read, tap, long_click, scroll_forward, scroll_backward, set_text
 * (all node-based via token), and search. Every successful write operation auto-captures
 * the updated screen tree after execution.
 */
class ScreenOperationAccessibilityBuiltin : RawBuiltinTool() {
    override val name = "screen_operation_accessibility"
    override val defaultEnabled = true
    override val description: String =
        "Screen interaction via accessibility service. " +
                "Operations: read (capture YAML tree), tap, long_click, scroll_forward, " +
                "scroll_backward, set_text, search. Target nodes by token (format: " +
                "version_index, e.g. \"a3f2c91e7b40_42\") from the most recently returned " +
                "snapshot. Every successful write " +
                "op auto-captures the updated tree — no separate read needed.\n\n" +
                "YAML fields: token=node_identifier, " +
                "t=semantic_type(button/input/text/image/list/list_item/switch/checkbox/tab/chip/toolbar/dialog/container), " +
                "b=bounds[left,top,right,bottom], pos=3x3_grid_position, txt=display_text, h=content_description, " +
                "tap=clickable, hold=long_clickable, edit=editable, scroll=scrollable, " +
                "checked=checked_state, ch=children, more=off_screen_children_summaries.\n\n" +
                "search: case-insensitive keyword match on txt/h. " +
                "keywords: [\"term1\", \"term2\"] (required, JSON string array). " +
                "match_mode: \"any\" (default) | \"all\". " +
                "limit: max results (default 10). " +
                "Returns matched nodes with tokens + version header.\n\n" +
                "If read returns root-only or empty tree: app likely uses non-native UI " +
                "(Flutter/Unity/WebView) — stop, do not retry.\n\n" +
                "wait_mode (default \"stable\"): \"stable\" detects when the UI actually settles " +
                "(event idle + tree hash) and returns early — use for taps, scrolls, text input. " +
                "\"delay\" does a blind fixed wait — use for search/refresh where data arrives " +
                "asynchronously and the UI may appear stable before results load. " +
                "Must be \"stable\" or \"delay\".\n" +
                "wait_ms (default 2000, max 60000): for \"stable\" this is the max deadline; " +
                "for \"delay\" this is the fixed blind-wait duration.\n\n" +
                "Tokens belong to exactly one snapshot — every read, search, and successful " +
                "write operation produces a fresh version. Use only tokens from the most " +
                "recently returned result."

    override fun configure(config: LocalToolConfig) {
        config.description = description
        config.string("operation") {
            description = "Which operation: read, tap, long_click, scroll_forward, scroll_backward, set_text, search."
            required = true
        }
        config.string("token") {
            description = "Node token from the latest snapshot result (format: version_index). Required for tap, long_click, scroll_forward, scroll_backward, set_text."
            required = false
        }
        config.string("text") {
            description = "Text to type into the field. Required for set_text."
            required = false
        }
        config.string("match_mode") {
            description = "Search match mode: \"any\" (default) to match any keyword, \"all\" to require all keywords."
            required = false
        }
        config.number("limit") {
            description = "Max search results to return, default 10."
            required = false
        }
        config.string("wait_mode") {
            description = "\"stable\" (default): detect UI stability before capture, returns early if settled. \"delay\": blind fixed wait — use for search/refresh. Must be \"stable\" or \"delay\"."
            required = false
        }
        config.number("wait_ms") {
            description = "Wait duration in ms, default 2000, max 60000. For stable mode: max deadline. For delay mode: fixed sleep."
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
                is ScreenOp.Read -> {
                    val capture = captureAfterOptionalWait(args)
                    capture
                        .fold(
                            onSuccess = { it.yaml },
                            onFailure = { e ->
                                errorJson("SERVICE_UNAVAILABLE", e.message ?: "Service unavailable")
                            },
                        )
                }

                is ScreenOp.Tap -> executeNodeActionAndCapture(
                    op.token, NodeAction.CLICK, null, args.waitMode, args.waitMs
                )

                is ScreenOp.LongClick -> executeNodeActionAndCapture(
                    op.token, NodeAction.LONG_CLICK, null, args.waitMode, args.waitMs
                )

                is ScreenOp.ScrollForward -> executeNodeActionAndCapture(
                    op.token, NodeAction.SCROLL_FORWARD, null, args.waitMode, args.waitMs
                )

                is ScreenOp.ScrollBackward -> executeNodeActionAndCapture(
                    op.token, NodeAction.SCROLL_BACKWARD, null, args.waitMode, args.waitMs
                )

                is ScreenOp.SetText -> executeNodeActionAndCapture(
                    op.token, NodeAction.SET_TEXT, op.text, args.waitMode, args.waitMs
                )

                is ScreenOp.Search -> {
                    waitBeforeSearch(args)
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
     * Executes a node action, then captures the updated screen according to [waitMode].
     *
     * Returns the YAML representation on success, or an error JSON string on failure.
     */
    private suspend fun executeNodeActionAndCapture(
        token: String,
        action: NodeAction,
        text: String?,
        waitMode: String,
        waitMs: Long,
    ): String {
        val actionResult = AccessibilityController.executeNodeAction(token, action, text)
        if (!actionResult.ok) {
            return errorJson(actionResult.code, actionResult.message)
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
            AccessibilityController.captureScreenAfterDelay(args.waitMs)
        } else {
            AccessibilityController.waitForStable(args.waitMs)
        }
    }

    private fun errorJson(code: String, message: String): String {
        return ScreenOperationError.errorJson(code, message)
    }
}
