package com.niki914.nexus.agentic.chat.agentic.custom

import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRegistry
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolResult
import com.niki914.nexus.agentic.chat.agentic.shell.ShellCommandSafetyPolicy
import com.niki914.nexus.agentic.runtime.settings.RuntimeEnvironment
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeCustomTool as CustomTool
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeCustomToolValidation as CustomToolValidation

data class CustomToolCreateRequest(
    val name: String,
    val description: String,
    val command: String,
    val enabled: Boolean = false,
    val overwrite: Boolean = false,
)

data class CustomToolConfig(
    val name: String,
    val description: String,
    val enabled: Boolean,
    val command: String,
)

class CustomToolManager(
    private val reservedToolNames: () -> Set<String> = {
        BuiltinToolRegistry.default().all().map { it.name }.toSet()
    },
    private val safetyPolicy: ShellCommandSafetyPolicy = ShellCommandSafetyPolicy(),
) {
    suspend fun load(): List<CustomToolConfig> {
        val gateway = RuntimeEnvironment.awaitSettingsGateway()
        return gateway.listCustomTools().map { it.toConfig() }
    }

    suspend fun createOrUpdate(
        request: CustomToolCreateRequest,
    ): BuiltinToolResult {
        return withSettingsFailure {
            val existingItems = load()
            val validationError = validate(
                request = request,
                existingNames = existingItems.map { it.name }.toSet(),
                reservedNames = reservedNames(),
            )
            if (validationError != null) {
                return@withSettingsFailure validationError
            }

            val normalized = request.toConfig()
            val gateway = RuntimeEnvironment.awaitSettingsGateway()
            val repoValidation =
                gateway.saveCustomTool(normalized.toRuntimeModel(), overwrite = true)
            if (repoValidation != null) {
                return@withSettingsFailure repoValidation.toFailure()
            }

            successForTool(normalized)
        }
    }

    suspend fun saveAll(
        items: List<CustomToolConfig>,
    ): BuiltinToolResult {
        return withSettingsFailure {
            val normalizedItems = items.map { it.normalized() }
            val validationError = validateAll(
                items = normalizedItems,
                reservedNames = reservedNames(),
            )
            if (validationError != null) {
                return@withSettingsFailure validationError
            }

            val gateway = RuntimeEnvironment.awaitSettingsGateway()
            val repoValidation =
                gateway.replaceAllCustomTools(normalizedItems.map { it.toRuntimeModel() })
            if (repoValidation != null) {
                return@withSettingsFailure repoValidation.toFailure()
            }

            BuiltinToolResult.success(
                message = "Custom tools were saved.",
                hint = "The updated custom tools are available after the next runtime refresh.",
                data = JsonObject(
                    mapOf(
                        "available_next_turn" to JsonPrimitive(true),
                        "count" to JsonPrimitive(normalizedItems.size),
                    )
                ),
            )
        }
    }

    suspend fun delete(name: String): BuiltinToolResult {
        return withSettingsFailure {
            val gateway = RuntimeEnvironment.awaitSettingsGateway()
            gateway.deleteCustomTool(name)
            BuiltinToolResult.success(
                message = "Custom tool '$name' was deleted.",
                hint = "The updated custom tools are available after the next runtime refresh.",
                data = JsonObject(
                    mapOf(
                        "available_next_turn" to JsonPrimitive(true),
                        "name" to JsonPrimitive(name),
                    )
                ),
            )
        }
    }

    suspend fun setEnabled(name: String, enabled: Boolean): BuiltinToolResult {
        return withSettingsFailure {
            val gateway = RuntimeEnvironment.awaitSettingsGateway()
            gateway.setCustomToolEnabled(name, enabled)
            BuiltinToolResult.success(
                message = "Custom tool setting updated.",
                hint = "The updated custom tools are available after the next runtime refresh.",
                data = JsonObject(
                    mapOf(
                        "available_next_turn" to JsonPrimitive(true),
                        "name" to JsonPrimitive(name),
                        "enabled" to JsonPrimitive(enabled),
                    )
                ),
            )
        }
    }

    suspend fun validate(
        request: CustomToolCreateRequest,
        existingNames: Set<String>,
        reservedNames: Set<String>,
    ): BuiltinToolResult? {
        val normalized = request.normalized()
        val fieldErrors = mutableMapOf<String, String>()

        if (normalized.name.isBlank()) {
            fieldErrors["name"] = "Required field 'name' is missing."
        } else if (!NAME_PATTERN.matches(normalized.name)) {
            return BuiltinToolResult.failure(
                code = "INVALID_NAME",
                message = "Custom tool name '${normalized.name}' is invalid.",
                hint = "Use 2-64 characters matching ^[a-zA-Z_][a-zA-Z0-9_]{1,63}$, for example battery_status.",
                fieldErrors = mapOf("name" to "Name must start with a letter or underscore and contain only letters, digits, or underscores."),
            )
        }

        if (normalized.description.isBlank()) {
            fieldErrors["description"] = "Required field 'description' is missing."
        }
        if (normalized.command.isBlank()) {
            fieldErrors["command"] = "Required field 'command' is missing."
        }
        if (fieldErrors.isNotEmpty()) {
            return BuiltinToolResult.failure(
                code = "MISSING_REQUIRED_FIELD",
                message = "Custom tool requires non-empty name, description, and command.",
                hint = "Provide all required fields before creating the custom tool.",
                fieldErrors = fieldErrors,
            )
        }

        if (normalized.name in reservedNames) {
            return BuiltinToolResult.failure(
                code = "RESERVED_NAME",
                message = "Custom tool name '${normalized.name}' is reserved by a builtin tool.",
                hint = "Choose a different name that does not conflict with builtin tools.",
                fieldErrors = mapOf("name" to "Reserved builtin tool name."),
            )
        }
        if (normalized.name in existingNames && !normalized.overwrite) {
            return BuiltinToolResult.failure(
                code = "NAME_CONFLICT",
                message = "Custom tool name '${normalized.name}' already exists.",
                hint = "Use a different name, or set overwrite=true if replacement is intended.",
                fieldErrors = mapOf("name" to "Already exists in custom_tools."),
            )
        }
        val decision = safetyPolicy.evaluate(normalized.command)
        if (!decision.allowed) {
            val ruleName = decision.matchedRuleName ?: "unknown"
            return BuiltinToolResult.failure(
                code = decision.code,
                message = "Custom tool '${normalized.name}' is blocked by execution rule '$ruleName'.",
                hint = decision.reason,
                fieldErrors = mapOf("command" to decision.reason),
            )
        }

        return null
    }

    private suspend fun validateAll(
        items: List<CustomToolConfig>,
        reservedNames: Set<String>,
    ): BuiltinToolResult? {
        val names = mutableSetOf<String>()
        items.forEach { item ->
            val request = CustomToolCreateRequest(
                name = item.name,
                description = item.description,
                command = item.command,
                enabled = item.enabled,
                overwrite = false,
            )
            val validationError = validate(
                request = request,
                existingNames = emptySet(),
                reservedNames = reservedNames,
            )
            if (validationError != null) {
                return validationError
            }
            if (!names.add(item.name)) {
                return BuiltinToolResult.failure(
                    code = "NAME_CONFLICT",
                    message = "Custom tool name '${item.name}' appears more than once.",
                    hint = "Each custom tool name must be unique.",
                    fieldErrors = mapOf("name" to "Duplicate name in custom_tools."),
                )
            }
        }
        return null
    }

    private fun successForTool(
        tool: CustomToolConfig,
    ): BuiltinToolResult {
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

    private fun reservedNames(): Set<String> {
        return reservedToolNames()
    }

    private fun CustomToolCreateRequest.normalized(): CustomToolCreateRequest {
        return copy(
            name = name.trim(),
            description = description.trim(),
            command = command.trim(),
        )
    }

    private fun CustomToolCreateRequest.toConfig(): CustomToolConfig {
        val normalized = normalized()
        return CustomToolConfig(
            name = normalized.name,
            description = normalized.description,
            enabled = normalized.enabled,
            command = normalized.command,
        )
    }

    private fun CustomToolConfig.normalized(): CustomToolConfig {
        return copy(
            name = name.trim(),
            description = description.trim(),
            command = command.trim(),
        )
    }

    private fun CustomToolConfig.toRuntimeModel(): CustomTool {
        return CustomTool(
            name = name,
            description = description,
            command = command,
            enabled = enabled,
        )
    }

    private fun CustomTool.toConfig(): CustomToolConfig {
        return CustomToolConfig(
            name = name,
            description = description,
            enabled = enabled,
            command = command,
        )
    }

    private suspend inline fun withSettingsFailure(block: suspend () -> BuiltinToolResult): BuiltinToolResult {
        return try {
            block()
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

    private fun CustomToolValidation.toFailure(): BuiltinToolResult {
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
            message = "Custom tool validation failed.",
            hint = message,
            fieldErrors = mapOf(field to message),
        )
    }

    companion object {
        private val NAME_PATTERN = Regex("^[a-zA-Z_][a-zA-Z0-9_]{1,63}$")
    }
}
