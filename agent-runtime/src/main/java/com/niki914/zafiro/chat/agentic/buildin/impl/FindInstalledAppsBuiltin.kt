package com.niki914.zafiro.chat.agentic.buildin.impl

import com.niki914.zafiro.chat.agentic.buildin.BuiltinTool
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolResult
import com.niki914.zafiro.chat.agentic.device.AppInfo
import com.niki914.zafiro.chat.agentic.device.AppInfoProvider
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

class FindInstalledAppsBuiltin : BuiltinTool() {
    override val name: String = "find_installed_apps"

    override val description: String =
        "Find installed apps on this device by app name or package name. Serves opening apps only: use it to resolve which app to open. Not a general search tool."

    override val defaultEnabled: Boolean = true

    override val inputSchemaJson: String? get() = FIND_INSTALLED_APPS_SCHEMA

    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
        val args = try {
            parseArguments(request.argumentsJson)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) {
                throw throwable
            }
            return BuiltinToolResult.failure(
                code = "INVALID_ARGUMENTS_JSON",
                message = "find_installed_apps arguments must be a JSON object with a query field.",
                hint = """Example: {"query":"微信","include_system":false,"limit":10}""",
                fieldErrors = mapOf(
                    "argumentsJson" to (throwable.message ?: "Invalid JSON object.")
                ),
            )
        }

        if (args.query.isBlank()) {
            return BuiltinToolResult.failure(
                code = "MISSING_REQUIRED_FIELD",
                message = "find_installed_apps requires a non-blank query.",
                fieldErrors = mapOf("query" to "Field 'query' must not be blank."),
            )
        }

        val apps = AppInfoProvider.cache().search(
            query = args.query,
            includeSystem = args.includeSystem,
            limit = args.limit,
        )
        return BuiltinToolResult.success(
            message = if (apps.isEmpty()) "No matching apps found." else "Matching apps found.",
            data = JsonObject(
                mapOf(
                    "query" to JsonPrimitive(args.query),
                    "include_system" to JsonPrimitive(args.includeSystem),
                    "apps" to apps.toJsonArray(),
                )
            ),
        )
    }

    private fun parseArguments(argumentsJson: String): FindInstalledAppsArguments {
        val element = try {
            Json.parseToJsonElement(argumentsJson)
        } catch (throwable: SerializationException) {
            throw IllegalArgumentException("argumentsJson is not valid JSON.", throwable)
        } catch (throwable: IllegalArgumentException) {
            throw IllegalArgumentException("argumentsJson is not valid JSON.", throwable)
        }
        val obj = element as? JsonObject
            ?: throw IllegalArgumentException("argumentsJson must be a JSON object.")
        return FindInstalledAppsArguments(
            query = obj.string("query").trim(),
            includeSystem = obj["include_system"]?.jsonPrimitive?.booleanOrNull ?: false,
            limit = obj["limit"]?.jsonPrimitive?.intOrNull ?: DEFAULT_LIMIT,
        )
    }

    private fun List<AppInfo>.toJsonArray(): JsonArray {
        return JsonArray(
            map { app ->
                JsonObject(
                    mapOf(
                        "app_name" to JsonPrimitive(app.appName),
                        "package_name" to JsonPrimitive(app.packageName),
                        "is_system_app" to JsonPrimitive(app.isSystemApp),
                    )
                )
            }
        )
    }

    private fun JsonObject.string(key: String): String {
        return this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
    }

    private data class FindInstalledAppsArguments(
        val query: String,
        val includeSystem: Boolean,
        val limit: Int,
    )

    companion object {
        private const val DEFAULT_LIMIT = 10
        private const val FIND_INSTALLED_APPS_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "query": {
                  "type": "string",
                  "description": "App name or package name fragment to resolve."
                },
                "include_system": {
                  "type": "boolean",
                  "description": "Whether to include system apps in results. Defaults to false."
                },
                "limit": {
                  "type": "integer",
                  "description": "Maximum number of results, from 1 to 20. Defaults to 10."
                }
              },
              "required": ["query"]
            }
        """
    }
}
