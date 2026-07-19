package com.niki914.nexus.agentic.chat.agentic.buildin.impl

import com.niki914.nexus.agentic.chat.agentic.accessibility.AccessibilityController
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.nexus.agentic.chat.agentic.buildin.RawBuiltinTool
import com.niki914.s3ss10n.LocalToolConfig
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

class SearchNodesBuiltin : RawBuiltinTool() {
    override val name: String = "search_nodes"

    override val description: String =
        "Search the current screen's accessibility tree for nodes matching keywords. " +
            "Use this before screen_content when you need to locate specific UI elements " +
            "in a large tree — narrow down target indices first, then use node_action.\n\n" +
            "Key use case: when the task is \"find and tap button X in a sea of nodes\", " +
            "call search_nodes(keywords=[\"X\"]) first to get candidate indices, rather " +
            "than parsing the full screen_content YAML.\n\n" +
            "Parameters (JSON):\n" +
            "- keywords: string array, required. Case-insensitive substring match on txt/h.\n" +
            "- match_mode: \"any\" (default, match any keyword) or \"all\" (match all keywords).\n" +
            "- limit: integer, default 10, max results returned.\n\n" +
            "Returns YAML with matched node indices that can be used directly with node_action."

    override val defaultEnabled: Boolean = true

    override fun configure(config: LocalToolConfig) {
        config.description = description
    }

    override suspend fun invokeRaw(request: BuiltinToolRequest): String {
        return try {
            val args = parseArguments(request.argumentsJson)

            if (args.keywords.isEmpty()) {
                return "error: keywords must be a non-empty array of strings"
            }

            val result = AccessibilityController.searchNodes(
                keywords = args.keywords,
                matchMode = args.matchMode,
                limit = args.limit,
            )
            result.fold(
                onSuccess = { it },
                onFailure = { "error: ${it.message}" },
            )
        } catch (e: IllegalArgumentException) {
            "error: ${e.message}"
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            "error: ${throwable.message}"
        }
    }

    private fun parseArguments(argumentsJson: String): SearchNodesArguments {
        val element = try {
            Json.parseToJsonElement(argumentsJson)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("argumentsJson is not valid JSON.", e)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("argumentsJson is not valid JSON.", e)
        }
        val obj = element as? JsonObject
            ?: throw IllegalArgumentException("argumentsJson must be a JSON object.")

        val keywords = obj["keywords"]?.jsonArray?.map {
            it.jsonPrimitive.contentOrNull?.trim() ?: ""
        }?.filter { it.isNotEmpty() } ?: emptyList()

        val matchMode = obj["match_mode"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
            ?.takeIf { it == "any" || it == "all" } ?: "any"

        val limit = obj["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.coerceAtLeast(1) ?: 10

        return SearchNodesArguments(keywords, matchMode, limit)
    }

    private data class SearchNodesArguments(
        val keywords: List<String>,
        val matchMode: String,
        val limit: Int,
    )
}
