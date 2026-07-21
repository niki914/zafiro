package com.niki914.nexus.agentic.chat.agentic.buildin.impl

import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinTool
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolResult
import com.niki914.nexus.agentic.chat.agentic.device.AppInfo
import com.niki914.nexus.agentic.chat.agentic.device.AppInfoProvider
import com.niki914.s3ss10n.LocalToolConfig
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

class SearchAppsBuiltin : BuiltinTool() {
    override val name: String = "search_apps"

    override val description: String =
        "Search installed Android apps by app name or package name. Use this before launch_app when the requested app name may be ambiguous."

    override val defaultEnabled: Boolean = true

    override fun configure(config: LocalToolConfig) {
        config.description = description
        config.string("query") {
            description = "App name or package name fragment to search."
            required = true
        }
        config.boolean("include_system") {
            description = "Whether to include system apps in results. Defaults to false."
            required = false
        }
        config.rawJsonSchema(SEARCH_APPS_SCHEMA)
    }

    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
        val args = try {
            parseArguments(request.argumentsJson)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) {
                throw throwable
            }
            return BuiltinToolResult.failure(
                code = "INVALID_ARGUMENTS_JSON",
                message = "search_apps arguments must be a JSON object with a query field.",
                hint = """Example: {"query":"微信","include_system":false,"limit":10}""",
                fieldErrors = mapOf(
                    "argumentsJson" to (throwable.message ?: "Invalid JSON object.")
                ),
            )
        }

        if (args.query.isBlank()) {
            return BuiltinToolResult.failure(
                code = "MISSING_REQUIRED_FIELD",
                message = "search_apps requires a non-blank query.",
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

    private fun parseArguments(argumentsJson: String): SearchAppsArguments {
        val element = try {
            Json.parseToJsonElement(argumentsJson)
        } catch (throwable: SerializationException) {
            throw IllegalArgumentException("argumentsJson is not valid JSON.", throwable)
        } catch (throwable: IllegalArgumentException) {
            throw IllegalArgumentException("argumentsJson is not valid JSON.", throwable)
        }
        val obj = element as? JsonObject
            ?: throw IllegalArgumentException("argumentsJson must be a JSON object.")
        return SearchAppsArguments(
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

    private data class SearchAppsArguments(
        val query: String,
        val includeSystem: Boolean,
        val limit: Int,
    )

    companion object {
        private const val DEFAULT_LIMIT = 10
        private const val SEARCH_APPS_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "query": {
                  "type": "string",
                  "description": "App name or package name fragment to search."
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
