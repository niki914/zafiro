package com.niki914.nexus.agentic.chat.agentic.buildin.impl

import android.content.Context
import android.content.Intent
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinTool
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolResult
import com.niki914.nexus.agentic.chat.agentic.device.AppInfo
import com.niki914.nexus.agentic.chat.agentic.device.AppInfoProvider
import com.niki914.nexus.agentic.chat.agentic.device.AppMatchResult
import com.niki914.nexus.xposed.api.util.ContextProvider
import com.niki914.s3ss10n.LocalToolConfig
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class LaunchAppBuiltin : BuiltinTool() {
    override val name: String = "launch_app"

    override val description: String =
        "Launch an installed Android app by package_name or fuzzy app_name. If app_name matches multiple apps, this tool returns all candidates with app names and package names instead of launching."

    override val defaultEnabled: Boolean = true

    override fun configure(config: LocalToolConfig) {
        config.description = description
        config.string("package_name") {
            description = "Exact Android package name to launch, for example com.tencent.mm."
            required = false
        }
        config.string("app_name") {
            description = "App display name to launch. Fuzzy matching is allowed; ambiguous matches return candidates."
            required = false
        }
        config.rawJsonSchema(LAUNCH_APP_SCHEMA)
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
                message = "launch_app arguments must be a JSON object with package_name or app_name.",
                hint = """Example: {"app_name":"微信"} or {"package_name":"com.tencent.mm"}""",
                fieldErrors = mapOf("argumentsJson" to (throwable.message ?: "Invalid JSON object.")),
            )
        }

        if (args.packageName.isNullOrBlank() && args.appName.isNullOrBlank()) {
            return BuiltinToolResult.failure(
                code = "MISSING_REQUIRED_FIELD",
                message = "launch_app requires package_name or app_name.",
                fieldErrors = mapOf("package_name" to "Provide package_name or app_name."),
            )
        }

        val packageName = args.packageName?.takeIf { it.isNotBlank() }
            ?: return launchByAppName(args.appName.orEmpty())
        return launchPackage(packageName = packageName, appInfo = null)
    }

    private suspend fun launchByAppName(appName: String): BuiltinToolResult {
        return when (val result = AppInfoProvider.cache().findByAppName(appName)) {
            is AppMatchResult.Found -> launchPackage(
                packageName = result.app.packageName,
                appInfo = result.app,
            )

            is AppMatchResult.Candidates -> BuiltinToolResult.failure(
                code = "AMBIGUOUS_APP_MATCH",
                message = "Multiple apps match '$appName'. Call launch_app again with one package_name from candidates.",
                hint = "Use one exact package_name from data.candidates.",
                data = JsonObject(
                    mapOf(
                        "app_name" to JsonPrimitive(appName),
                        "candidates" to result.apps.toJsonArray(),
                    )
                ),
            )

            AppMatchResult.NotFound -> BuiltinToolResult.failure(
                code = "APP_NOT_FOUND",
                message = "No installed app matches '$appName'.",
                hint = "Try search_apps with a broader query.",
                data = JsonObject(mapOf("app_name" to JsonPrimitive(appName))),
            )
        }
    }

    private suspend fun launchPackage(
        packageName: String,
        appInfo: AppInfo?,
    ): BuiltinToolResult {
        val context = ContextProvider.await().applicationContext
        val event = context.startApp(packageName)
        val data = linkedMapOf<String, JsonElement>(
            "package_name" to JsonPrimitive(packageName),
        )
        appInfo?.let {
            data["app_name"] = JsonPrimitive(it.appName)
            data["is_system_app"] = JsonPrimitive(it.isSystemApp)
        }
        return when (event) {
            LaunchEvent.Launched -> BuiltinToolResult.success(
                message = "App launched.",
                data = JsonObject(data),
            )

            is LaunchEvent.Failed -> BuiltinToolResult.failure(
                code = "APP_LAUNCH_FAILED",
                message = event.message,
                hint = "Confirm the package exists and has a launcher activity.",
                data = JsonObject(data),
            )
        }
    }

    private fun Context.startApp(packageName: String): LaunchEvent {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: return LaunchEvent.Failed("No launcher activity found for package '$packageName'.")
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            startActivity(launchIntent)
            LaunchEvent.Launched
        } catch (throwable: Throwable) {
            LaunchEvent.Failed(throwable.message ?: "Failed to launch package '$packageName'.")
        }
    }

    private fun parseArguments(argumentsJson: String): LaunchAppArguments {
        val element = try {
            Json.parseToJsonElement(argumentsJson)
        } catch (throwable: SerializationException) {
            throw IllegalArgumentException("argumentsJson is not valid JSON.", throwable)
        } catch (throwable: IllegalArgumentException) {
            throw IllegalArgumentException("argumentsJson is not valid JSON.", throwable)
        }
        val obj = element as? JsonObject
            ?: throw IllegalArgumentException("argumentsJson must be a JSON object.")
        return LaunchAppArguments(
            packageName = obj.string("package_name").trim().ifBlank { null },
            appName = obj.string("app_name").trim().ifBlank { null },
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

    private data class LaunchAppArguments(
        val packageName: String?,
        val appName: String?,
    )

    private sealed interface LaunchEvent {
        data object Launched : LaunchEvent
        data class Failed(val message: String) : LaunchEvent
    }

    companion object {
        private const val LAUNCH_APP_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "package_name": {
                  "type": "string",
                  "description": "Exact Android package name to launch, for example com.tencent.mm."
                },
                "app_name": {
                  "type": "string",
                  "description": "App display name to launch. Fuzzy matching is allowed; ambiguous matches return candidates."
                }
              }
            }
        """
    }
}
