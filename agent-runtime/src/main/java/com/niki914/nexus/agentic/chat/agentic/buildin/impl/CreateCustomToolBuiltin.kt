package com.niki914.nexus.agentic.chat.agentic.buildin.impl

import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinTool
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolResult
import com.niki914.nexus.agentic.chat.agentic.custom.CustomToolCreateRequest
import com.niki914.nexus.agentic.runtime.settings.RuntimeEnvironment
import com.niki914.s3ss10n.LocalToolConfig
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeCustomTool as CustomTool
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeCustomToolValidation as CustomToolValidation

class CreateCustomToolBuiltin : BuiltinTool() {
    override val name: String = "create_custom_tool"

    override val description: String = "Create or update a custom tool setting."

    override val defaultEnabled: Boolean = true

    override fun configure(config: LocalToolConfig) {
        config.description = description
        config.string("name") {
            description = "Unique custom tool name matching ^[a-zA-Z_][a-zA-Z0-9_]{1,63}$."
            required = true
        }
        config.string("description") {
            description = "Human-readable description shown to the model and UI."
            required = true
        }
        config.string("command") {
            description =
                "Normal Android shell command executed by the custom tool. Use su -c 'cmd' for root; use `cd /path && cmd` when the command depends on a working directory."
            required = true
        }
        config.boolean("enabled") {
            description = "Whether the created custom tool is enabled. Defaults to false."
            required = false
        }
        config.boolean("overwrite") {
            description =
                "Whether to replace an existing custom tool with the same name. Defaults to false."
            required = false
        }
        config.rawJsonSchema(CREATE_CUSTOM_TOOL_SCHEMA)
    }

    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
        val createRequest = try {
            parseArguments(request.argumentsJson)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) {
                throw throwable
            }
            return BuiltinToolResult.failure(
                code = "INVALID_ARGUMENTS_JSON",
                message = "create_custom_tool arguments must be a JSON object with name, description, command, enabled, and overwrite fields.",
                hint = """Example: {"name":"battery_status","description":"Read current battery status.","command":"dumpsys battery","enabled":false,"overwrite":false}""",
                fieldErrors = mapOf(
                    "argumentsJson" to (throwable.message ?: "Invalid JSON object.")
                ),
            )
        }

        return try {
            val normalized = createRequest.normalized()
            val tool = CustomTool(
                name = normalized.name,
                description = normalized.description,
                command = normalized.command,
                enabled = normalized.enabled,
            )
            val gateway = RuntimeEnvironment.awaitSettingsGateway()
            val validation = gateway.saveCustomTool(tool, overwrite = normalized.overwrite)
            if (validation != null) {
                validation.toFailure(tool.name)
            } else {
                successForTool(tool)
            }
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) {
                throw throwable
            }
            BuiltinToolResult.failure(
                code = "SETTINGS_WRITE_FAILED",
                message = "Failed to write custom tool settings: ${throwable.message ?: throwable::class.java.simpleName}.",
                hint = "Retry after confirming the settings provider is available.",
            )
        }
    }

    private fun parseArguments(argumentsJson: String): CustomToolCreateRequest {
        val element = try {
            Json.parseToJsonElement(argumentsJson)
        } catch (throwable: SerializationException) {
            throw IllegalArgumentException("argumentsJson is not valid JSON.", throwable)
        } catch (throwable: IllegalArgumentException) {
            throw IllegalArgumentException("argumentsJson is not valid JSON.", throwable)
        }
        val obj = element as? JsonObject
            ?: throw IllegalArgumentException("argumentsJson must be a JSON object.")
        return CustomToolCreateRequest(
            name = obj.string("name"),
            description = obj.string("description"),
            command = obj.string("command"),
            enabled = obj.boolean("enabled", default = false),
            overwrite = obj.boolean("overwrite", default = false),
        )
    }

    private fun JsonObject.string(key: String): String {
        return this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
    }

    private fun JsonObject.boolean(key: String, default: Boolean): Boolean {
        return (this[key] as? JsonPrimitive)?.booleanOrNull ?: default
    }

    private fun CustomToolCreateRequest.normalized(): CustomToolCreateRequest {
        return copy(
            name = name.trim(),
            description = description.trim(),
            command = command.trim(),
        )
    }

    private fun successForTool(tool: CustomTool): BuiltinToolResult {
        return BuiltinToolResult.success(
            message = "Custom tool '${tool.name}' was saved.",
            hint = "The tool is available after the next runtime refresh.",
            data = JsonObject(
                mapOf(
                    "available_next_turn" to JsonPrimitive(true),
                    "tool" to JsonObject(
                        mapOf(
                            "name" to JsonPrimitive(tool.name),
                            "enabled" to JsonPrimitive(tool.enabled),
                        )
                    ),
                )
            ),
        )
    }

    private fun CustomToolValidation.toFailure(toolName: String): BuiltinToolResult {
        return BuiltinToolResult.failure(
            code = when {
                message == "Reserved builtin tool name." -> "RESERVED_NAME"
                message == "Already exists in custom_tools." -> "NAME_CONFLICT"
                field == "command" && message.contains(
                    "execution rule",
                    ignoreCase = true
                ) -> "RULE_BLOCKED"

                else -> "INVALID_CUSTOM_TOOL"
            },
            message = when {
                message == "Reserved builtin tool name." -> "Custom tool name '$toolName' is reserved by a builtin tool."
                message == "Already exists in custom_tools." -> "Custom tool name '$toolName' already exists."
                field == "command" && message.contains(
                    "execution rule",
                    ignoreCase = true
                ) -> message

                else -> "Custom tool validation failed."
            },
            hint = message,
            fieldErrors = mapOf(field to message),
        )
    }

    companion object {
        private const val CREATE_CUSTOM_TOOL_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "name": {
                  "type": "string",
                  "description": "Unique custom tool name matching ^[a-zA-Z_][a-zA-Z0-9_]{1,63}$."
                },
                "description": {
                  "type": "string",
                  "description": "Human-readable description shown to the model and UI."
                },
                "command": {
                  "type": "string",
                  "description": "Normal Android shell command executed by the custom tool. Use su -c 'cmd' for root; use `cd /path && cmd` when the command depends on a working directory."
                },
                "enabled": {
                  "type": "boolean",
                  "description": "Whether the created custom tool is enabled.",
                  "default": false
                },
                "overwrite": {
                  "type": "boolean",
                  "description": "Whether to replace an existing custom tool with the same name.",
                  "default": false
                }
              },
              "required": ["name", "description", "command"]
            }
        """
    }
}
