package com.niki914.nexus.agentic.chat.agentic.buildin.impl

import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinTool
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolResult
import com.niki914.nexus.agentic.chat.agentic.buildin.RawJsonBuiltinTool
import com.niki914.nexus.agentic.runtime.settings.MemoryMutationResult
import com.niki914.nexus.agentic.runtime.settings.RuntimeEnvironment
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class MemoryBuiltin : BuiltinTool(), RawJsonBuiltinTool {
    override val name: String = "memory"

    override val description: String =
        "Save durable facts to persistent memory that survive across sessions. Memory is " +
            "injected into every future turn, so keep entries compact and high-signal.\n\n" +
            "WHEN: save proactively when the user states a preference, correction, or personal " +
            "detail, or you learn a stable fact about their environment, conventions, or workflow. " +
            "Priority: user preferences & corrections > environment facts > procedures. The best " +
            "memory stops the user repeating themselves.\n\n" +
            "ACTIONS: add (save a new fact), replace (update an existing entry), remove (delete " +
            "an entry). Use old_text — a short unique substring of the target entry — to identify " +
            "it for replace and remove.\n\n" +
            "SKIP: trivial/obvious info, easily re-discovered facts, raw data dumps, task progress, " +
            "completed-work logs, temporary TODO state. Reusable procedures belong in a skill, " +
            "not memory."

    override val defaultEnabled: Boolean = true

    override val inputSchemaJson: String? get() = MEMORY_SCHEMA

    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
        return BuiltinToolResult.failure(
            code = "RAW_JSON_ONLY",
            message = "$name must be executed through invokeRawJson().",
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
                    when (gateway.removeMemory(args.oldText!!)) {
                        MemoryMutationResult.Ok -> """{"ok":true,"action":"remove"}"""
                        MemoryMutationResult.NotFound -> BuiltinToolResult.failure(
                            code = "NOT_FOUND",
                            message = "No entry matched '${args.oldText}'.",
                            hint = "Check the exact text of the entry you want to remove.",
                        ).toJsonString()
                        MemoryMutationResult.Ambiguous -> BuiltinToolResult.failure(
                            code = "AMBIGUOUS_MATCH",
                            message = "Multiple entries matched '${args.oldText}'. Be more specific.",
                        ).toJsonString()
                    }
                }
                Action.REPLACE -> {
                    when (gateway.replaceMemory(args.oldText!!, args.content!!)) {
                        MemoryMutationResult.Ok -> """{"ok":true,"action":"replace"}"""
                        MemoryMutationResult.NotFound -> BuiltinToolResult.failure(
                            code = "NOT_FOUND",
                            message = "No entry matched '${args.oldText}'.",
                            hint = "Check the exact text of the entry you want to replace.",
                        ).toJsonString()
                        MemoryMutationResult.Ambiguous -> BuiltinToolResult.failure(
                            code = "AMBIGUOUS_MATCH",
                            message = "Multiple entries matched '${args.oldText}'. Be more specific.",
                        ).toJsonString()
                    }
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
        val oldText = obj["old_text"]?.jsonPrimitive?.contentOrNull?.trim()

        return Args(action, content, oldText)
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
                } else null
            }
            Action.REMOVE -> {
                if (args.oldText.isNullOrBlank()) {
                    BuiltinToolResult.failure(
                        code = "INVALID_ARGUMENTS",
                        message = "Field 'old_text' is required for remove action.",
                        hint = """Example: {"action":"remove","old_text":"User prefers"}""",
                    )
                } else null
            }
            Action.REPLACE -> {
                when {
                    args.oldText.isNullOrBlank() -> BuiltinToolResult.failure(
                        code = "INVALID_ARGUMENTS",
                        message = "Field 'old_text' is required for replace action.",
                        hint = """Example: {"action":"replace","old_text":"User prefers","content":"User prefers short answers."}""",
                    )
                    args.content.isNullOrBlank() -> BuiltinToolResult.failure(
                        code = "INVALID_ARGUMENTS",
                        message = "Field 'content' is required for replace action.",
                        hint = """Example: {"action":"replace","old_text":"User prefers","content":"User prefers short answers."}""",
                    )
                    else -> null
                }
            }
        }
    }

    private enum class Action {
        ADD, REMOVE, REPLACE;

        companion object {
            fun from(wire: String?): Action {
                return when (wire?.trim()?.lowercase()) {
                    "add" -> ADD
                    "remove" -> REMOVE
                    "replace" -> REPLACE
                    else -> throw IllegalArgumentException(
                        "Unknown action '${wire?.trim().orEmpty()}'. Expected add, replace, or remove."
                    )
                }
            }
        }
    }

    private data class Args(
        val action: Action,
        val content: String?,
        val oldText: String?,
    )

    companion object {
        private const val MEMORY_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "action": {
                  "type": "string",
                  "enum": ["add", "replace", "remove"],
                  "description": "The action to perform."
                },
                "content": {
                  "type": "string",
                  "description": "The entry content. Required for 'add' and 'replace'."
                },
                "old_text": {
                  "type": "string",
                  "description": "REQUIRED for 'replace' and 'remove': a short unique substring identifying the existing entry to modify. Omit only for 'add'."
                }
              },
              "required": ["action"]
            }
        """
    }
}
