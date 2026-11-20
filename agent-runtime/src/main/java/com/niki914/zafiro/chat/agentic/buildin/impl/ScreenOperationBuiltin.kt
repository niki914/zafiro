package com.niki914.zafiro.chat.agentic.buildin.impl

import com.niki914.zafiro.api.screen.NodeInfo
import com.niki914.zafiro.api.screen.Screen
import com.niki914.zafiro.api.screen.ScreenAction
import com.niki914.zafiro.api.screen.ScreenSnapshot
import com.niki914.zafiro.api.screen.Target
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.TextResultBuiltinTool
import com.niki914.zafiro.chat.agentic.buildin.TextToolResult
import com.niki914.zafiro.api.screen.ScreenTreeUnavailable
import com.niki914.zafiro.chat.agentic.currentAgentConversationId
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/**
 * 屏幕契约上的看和点。
 *
 * 参数原样交给 [com.niki914.zafiro.api.screen.Screen.snapshot] 或 `act`。
 * `search` 只在当次快照的节点上过滤。
 */
class ScreenOperationBuiltin : TextResultBuiltinTool() {
    override val name: String = "screen_operation"
    override val defaultEnabled: Boolean = true
    override val description: String =
        "Read and interact with a screen surface (UI tree node actions, gestures, keys, text injection, " +
            "search). Provide target 'screen' ('phone' for the default device display, or an id from screens) " +
            "and 'operation'."
    override val inputSchemaJson: String? = SCHEMA

    override suspend fun invokeText(request: BuiltinToolRequest): TextToolResult {
        val args = parseScreenObject(request.argumentsJson)
            ?: return TextToolResult.failure(
                code = SCREEN_INVALID_JSON,
                message = "arguments must be a JSON object.",
            )
        val id = args.screenId() ?: return invalidArguments("screen is required.")
        val operation = args.text("operation") ?: return invalidArguments("operation is required.")
        val screens = screenDirectory() ?: return missingService()
        val callerConvId = currentAgentConversationId()
        val (screen, failure) = resolveScreenOrFailure(screens, id, callerConvId)
        if (failure != null || screen == null) return failure ?: unknownScreen(id)
        return dispatch(screen, operation, args)
    }

    private suspend fun dispatch(
        screen: Screen,
        operation: String,
        args: JsonObject,
    ): TextToolResult {
        return when (operation) {
            "read" -> read(screen)
            "search" -> search(screen, args)
            "tap" -> actOnTarget(screen, args) { ScreenAction.Tap(it) }
            "long_click" -> actOnTarget(screen, args) { ScreenAction.LongClick(it) }
            "scroll_forward" -> actOnTarget(screen, args) { ScreenAction.Scroll(it, forward = true) }
            "scroll_backward" -> actOnTarget(screen, args) { ScreenAction.Scroll(it, forward = false) }
            "set_text" -> setText(screen, args)
            "swipe" -> swipe(screen, args)
            "key" -> key(screen, args)
            "history_back" -> actToText(screen.act(ScreenAction.History(forward = false)))
            "history_forward" -> actToText(screen.act(ScreenAction.History(forward = true)))
            "tabs" -> actToText(screen.act(ScreenAction.ListTabs))
            "new_tab" -> actToText(screen.act(ScreenAction.NewTab(args.text("url"))))
            "close_tab" -> tab(screen, args) { ScreenAction.CloseTab(it) }
            "switch_tab" -> tab(screen, args) { ScreenAction.SwitchTab(it) }
            else -> TextToolResult.failure(
                code = SCREEN_INVALID_OPERATION,
                message = "Unknown operation '$operation'.",
            )
        }
    }

    private suspend fun actOnTarget(
        screen: Screen,
        args: JsonObject,
        build: (Target) -> ScreenAction,
    ): TextToolResult {
        val target = args.target() ?: return invalidArguments("token or x and y is required.")
        return actToText(screen.act(build(target)))
    }

    private suspend fun setText(screen: Screen, args: JsonObject): TextToolResult {
        val token = args.text("token")?.let { parseToken(it) }
            ?: return invalidArguments("set_text requires a token.")
        val text = args.text("text") ?: return invalidArguments("set_text requires text.")
        return actToText(screen.act(ScreenAction.SetText(Target.Node(token), text)))
    }

    private suspend fun swipe(screen: Screen, args: JsonObject): TextToolResult {
        val x1 = args.int("start_x")
        val y1 = args.int("start_y")
        val x2 = args.int("end_x")
        val y2 = args.int("end_y")
        if (x1 == null || y1 == null || x2 == null || y2 == null) {
            return invalidArguments("swipe requires start_x, start_y, end_x, end_y.")
        }
        val duration = args.long("duration") ?: 300L
        return actToText(screen.act(ScreenAction.Swipe(x1, y1, x2, y2, duration)))
    }

    private suspend fun key(screen: Screen, args: JsonObject): TextToolResult {
        val code = args.int("code") ?: return invalidArguments("key requires code.")
        return actToText(screen.act(ScreenAction.Key(code)))
    }

    private suspend fun tab(
        screen: Screen,
        args: JsonObject,
        build: (String) -> ScreenAction,
    ): TextToolResult {
        val tabId = args.text("tab_id") ?: return invalidArguments("tab_id is required.")
        return actToText(screen.act(build(tabId)))
    }

    private suspend fun read(screen: Screen): TextToolResult {
        val snapshot = try {
            screen.snapshot()
        } catch (error: CancellationException) {
            throw error
        } catch (error: ScreenTreeUnavailable) {
            return captureFailure(error)
        }
        return TextToolResult.success(snapshot.yaml)
    }

    private suspend fun search(screen: Screen, args: JsonObject): TextToolResult {
        val keywords = args.strings("keywords")
        if (keywords.isNullOrEmpty()) return invalidArguments("search requires keywords.")
        val matchAll = args.text("match_mode") == "all"
        val limit = args.int("limit") ?: 10
        if (limit < 1) return invalidArguments("limit must be at least 1.")
        val snapshot = try {
            screen.snapshot()
        } catch (error: CancellationException) {
            throw error
        } catch (error: ScreenTreeUnavailable) {
            return captureFailure(error)
        }
        val hits = filterNodes(snapshot.nodes, keywords, matchAll, limit)
        return TextToolResult.success(searchPayload(snapshot, hits))
    }

    private fun captureFailure(error: ScreenTreeUnavailable): TextToolResult {
        return TextToolResult.failure(
            code = "CAPTURE_FAILED",
            message = error.message ?: "capture failed",
        )
    }

    private companion object {
        private val SCHEMA = """
            {
              "type": "object",
              "properties": {
                "screen": {
                  "type": "string",
                  "description": "Target screen ID, e.g. 'phone' for the default device display, or an id from screens."
                },
                "operation": {
                  "type": "string",
                  "description": "Operation to perform: read, tap, long_click, scroll_forward, scroll_backward, set_text, search, swipe, key, history_back, history_forward, tabs, new_tab, close_tab, switch_tab."
                },
                "token": {
                  "type": "string",
                  "description": "Target node token '{version}_{index}' from the latest read snapshot (e.g. 'a3f2_42'). Required for set_text; preferred for tap, long_click, scroll_*."
                },
                "x": {
                  "type": "number",
                  "description": "Physical X coordinate in screen pixels. Fallback for tap, long_click, scroll_* when target is not in node tree."
                },
                "y": {
                  "type": "number",
                  "description": "Y coordinate. Same as `x`." 
                },
                "text": {
                  "type": "string",
                  "description": "Text to type into targeted field. Required for set_text."
                },
                "keywords": {
                  "type": "array",
                  "items": { "type": "string" },
                  "description": "Keywords to search against node text and content descriptions. Required for search."
                },
                "match_mode": {
                  "type": "string",
                  "description": "Search match mode: 'any' (default) or 'all'."
                },
                "limit": {
                  "type": "number",
                  "description": "Maximum number of search results to return (default 10)."
                },
                "start_x": {
                  "type": "number",
                  "description": "Swipe start X in screen pixels. Required for swipe."
                },
                "start_y": {
                  "type": "number",
                  "description": "Swipe start Y. Same as `start_x`."
                },
                "end_x": {
                  "type": "number",
                  "description": "Swipe end X in screen pixels. Required for swipe."
                },
                "end_y": {
                  "type": "number",
                  "description": "Swipe end Y. Same as `end_x`."
                },
                "duration": {
                  "type": "number",
                  "description": "Swipe duration in milliseconds (default 300)."
                },
                "code": {
                  "type": "number",
                  "description": "Android key code to inject (e.g. 4 for BACK, 3 for HOME). Required for key."
                },
                "url": {
                  "type": "string",
                  "description": "URL to navigate to or open tab with."
                },
                "tab_id": {
                  "type": "string",
                  "description": "Browser tab identifier. Required for close_tab and switch_tab."
                },
                "ui_intent": {
                  "type": "string"
                }
              },
              "required": ["screen", "operation"]
            }
        """.trimIndent()
    }
}

internal fun filterNodes(
    nodes: List<NodeInfo>,
    keywords: List<String>,
    matchAll: Boolean,
    limit: Int,
): List<NodeInfo> {
    val keys = keywords.map { it.lowercase() }
    val hits = ArrayList<NodeInfo>()
    fun walk(node: NodeInfo) {
        if (hits.size >= limit) return
        val haystack = (node.text + " " + node.contentDesc).lowercase()
        val matched = if (matchAll) keys.all { it in haystack } else keys.any { it in haystack }
        if (matched) hits.add(node)
        node.children.forEach { walk(it) }
    }
    nodes.forEach { walk(it) }
    return hits
}

internal fun searchPayload(snapshot: ScreenSnapshot, hits: List<NodeInfo>): String {
    val body = hits.joinToString("\n") { node -> "${node.index}\t${node.text}" }
    return "version: ${snapshot.version}\nscreen: ${snapshot.screen.value}\n$body"
}
