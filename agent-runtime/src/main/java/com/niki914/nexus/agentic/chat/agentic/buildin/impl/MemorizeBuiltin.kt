package com.niki914.nexus.agentic.chat.agentic.buildin.impl

import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinTool
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolResult
import com.niki914.nexus.agentic.chat.agentic.buildin.RawJsonBuiltinTool
import com.niki914.nexus.agentic.runtime.settings.RuntimeEnvironment
import com.niki914.s3ss10n.LocalToolConfig
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class MemorizeBuiltin : BuiltinTool(), RawJsonBuiltinTool {
    override val name: String = "memorize"

    override val description: String =
        "Manage persistent memory across sessions. Use to save durable facts " +
            "(user preferences, environment details, conventions). " +
            "Memory items are shown in every turn's system prompt — " +
            "this is a write tool, NOT a query tool. " +
            "Use list to see saved items, add to save a new one, remove to delete by index."

    override val defaultEnabled: Boolean = true

    override fun configure(config: LocalToolConfig) {
        config.description = description
        config.rawJsonSchema(MEMORIZE_SCHEMA)
    }

    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
        return BuiltinToolResult.failure(
            code = "RAW_JSON_ONLY",
            message = "memorize must be executed through invokeRawJson().",
            hint = """Example: {"action":"add","content":"User prefers concise answers."}""",
        )
    }

    override suspend fun invokeRawJson(request: BuiltinToolRequest): String {
        val args = try {
            parseArgs(request.argumentsJson)
        } catch (error: CancellationException) {
            throw error
        } catch (error: IllegalArgumentException) {
            return BuiltinToolResult.failure(
                code = "INVALID_ARGUMENTS",
                message = error.message ?: "Invalid arguments.",
                hint = """Example: {"action":"add","content":"User prefers concise answers."}""",
            ).toJsonString()
        }

        val validationError = validateArgs(args)
        if (validationError != null) {
            return validationError.toJsonString()
        }

        return try {
            val gateway = RuntimeEnvironment.awaitSettingsGateway()
            when (args.action) {
                Action.ADD -> {
                    gateway.addMemory(args.content!!)
                    """{"ok":true,"action":"add"}"""
                }
                Action.REMOVE -> {
                    val index = args.index!!
                    val before = gateway.listMemories()
                    if (index !in before.indices) {
                        BuiltinToolResult.failure(
                            code = "INDEX_OUT_OF_RANGE",
                            message = "Memory index $index is out of range (0..${before.size - 1}).",
                            hint = "Use list to see current items and their indices.",
                        ).toJsonString()
                    } else {
                        gateway.deleteMemory(index)
                        """{"ok":true,"action":"remove","index":$index}"""
                    }
                }
                Action.LIST -> {
                    val items = gateway.listMemories()
                    val jsonItems = items.mapIndexed { i, text ->
                        JsonObject(mapOf("index" to JsonPrimitive(i), "content" to JsonPrimitive(text)))
                    }
                    JsonObject(mapOf("ok" to JsonPrimitive(true), "action" to JsonPrimitive("list"), "items" to JsonArray(jsonItems))).toString()
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            BuiltinToolResult.failure(
                code = "SETTINGS_WRITE_FAILED",
                message = "Failed to access memory: ${error.message ?: error::class.java.simpleName}.",
                hint = "Retry after confirming the settings provider is available.",
            ).toJsonString()
        }
    }

    private fun parseArgs(argumentsJson: String): Args {
        val element = try {
            Json.parseToJsonElement(argumentsJson)
        } catch (error: SerializationException) {
            throw IllegalArgumentException("argumentsJson is not valid JSON.")
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("argumentsJson is not valid JSON.")
        }
        val obj = element as? JsonObject
            ?: throw IllegalArgumentException("argumentsJson must be a JSON object.")

        val action = Action.from(obj["action"]?.jsonPrimitive?.contentOrNull)
        val content = obj["content"]?.jsonPrimitive?.contentOrNull?.trim()
        val index = obj["index"]?.jsonPrimitive?.intOrNull

        return Args(action, content, index)
    }

    private fun validateArgs(args: Args): BuiltinToolResult? {
        return when (args.action) {
            Action.ADD -> {
                if (args.content.isNullOrBlank()) {
                    BuiltinToolResult.failure(
                        code = "INVALID_ARGUMENTS",
                        message = "Field 'content' is required for add action.",
                        hint = """Example: {"action":"add","content":"User prefers concise answers."}""",
                    )
                } else {
                    null
                }
            }
            Action.REMOVE -> {
                if (args.index == null) {
                    BuiltinToolResult.failure(
                        code = "INVALID_ARGUMENTS",
                        message = "Field 'index' is required for remove action.",
                        hint = """Example: {"action":"remove","index":0}""",
                    )
                } else {
                    null
                }
            }
            Action.LIST -> null
        }
    }

    private enum class Action {
        ADD, REMOVE, LIST;

        companion object {
            fun from(wire: String?): Action {
                return when (wire?.trim()?.lowercase()) {
                    null, "", "add" -> ADD
                    "remove" -> REMOVE
                    "list" -> LIST
                    else -> throw IllegalArgumentException(
                        "Unknown action '${wire!!.trim()}'. Expected add, remove, or list."
                    )
                }
            }
        }
    }

    private data class Args(
        val action: Action,
        val content: String?,
        val index: Int?,
    )

    companion object {
        private const val MEMORIZE_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "action": {
                  "type": "string",
                  "enum": ["add", "remove", "list"],
                  "description": "add (save a fact), remove (delete by index), list (show all items with indices). Default: add."
                },
                "content": {
                  "type": "string",
                  "description": "The memory item text. Required for add."
                },
                "index": {
                  "type": "integer",
                  "description": "Zero-based index of the memory to remove. Required for remove. Use list to see indices."
                }
              }
            }
        """
    }
}
