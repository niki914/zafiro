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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class MemorizeBuiltin : BuiltinTool(), RawJsonBuiltinTool {
    override val name: String = "memorize"

    override val description: String = "Add a concise item to persistent memory."

    override val defaultEnabled: Boolean = true

    override fun configure(config: LocalToolConfig) {
        config.description = description
        config.string("content") {
            description = "The non-blank memory item to persist for future turns."
            required = true
        }
        config.rawJsonSchema(MEMORIZE_SCHEMA)
    }

    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
        return BuiltinToolResult.failure(
            code = "RAW_JSON_ONLY",
            message = "memorize must be executed through invokeRawJson().",
            hint = "Use BuiltinToolExecutor to execute this builtin.",
        )
    }

    override suspend fun invokeRawJson(request: BuiltinToolRequest): String {
        val content = try {
            parseContent(request.argumentsJson)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) {
                throw throwable
            }
            return BuiltinToolResult.failure(
                code = "INVALID_ARGUMENTS_JSON",
                message = "memorize arguments must be a JSON object with a content field.",
                hint = """Example: {"content":"The user prefers concise answers."}""",
                fieldErrors = mapOf(
                    "argumentsJson" to (throwable.message ?: "Invalid JSON object.")
                ),
            ).toJsonString()
        }
        if (content.isBlank()) {
            return BuiltinToolResult.failure(
                code = "MISSING_REQUIRED_FIELD",
                message = "memorize requires non-blank content.",
                hint = "Provide a concise memory item in the content field.",
                fieldErrors = mapOf("content" to "Field 'content' must not be blank."),
            ).toJsonString()
        }

        return try {
            RuntimeEnvironment.awaitSettingsGateway().addMemory(content)
            SUCCESS_JSON
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) {
                throw throwable
            }
            BuiltinToolResult.failure(
                code = "SETTINGS_WRITE_FAILED",
                message = "Failed to write memory: ${throwable.message ?: throwable::class.java.simpleName}.",
                hint = "Retry after confirming the settings provider is available.",
            ).toJsonString()
        }
    }

    private fun parseContent(argumentsJson: String): String {
        val element = try {
            Json.parseToJsonElement(argumentsJson)
        } catch (throwable: SerializationException) {
            throw IllegalArgumentException("argumentsJson is not valid JSON.", throwable)
        } catch (throwable: IllegalArgumentException) {
            throw IllegalArgumentException("argumentsJson is not valid JSON.", throwable)
        }
        val obj = element as? JsonObject
            ?: throw IllegalArgumentException("argumentsJson must be a JSON object.")
        return obj["content"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
    }

    companion object {
        private const val SUCCESS_JSON = """{"ok":true}"""
        private const val MEMORIZE_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "content": {
                  "type": "string",
                  "description": "The non-blank memory item to persist for future turns."
                }
              },
              "required": ["content"]
            }
        """
    }
}
