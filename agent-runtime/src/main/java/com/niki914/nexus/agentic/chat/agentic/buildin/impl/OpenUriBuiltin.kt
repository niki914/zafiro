package com.niki914.nexus.agentic.chat.agentic.buildin.impl

import android.content.Intent
import android.net.Uri
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinTool
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolResult
import com.niki914.nexus.xposed.api.util.ContextProvider
import com.niki914.s3ss10n.LocalToolConfig
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class OpenUriBuiltin : BuiltinTool() {
    override val name: String = "open_uri"

    override val description: String =
        "Open a known-valid Android URI with ACTION_VIEW, such as https, geo, tel, mailto, or an app deep link. Do not invent or guess URI schemes."

    override val defaultEnabled: Boolean = true

    override fun configure(config: LocalToolConfig) {
        config.description = description
        config.string("uri") {
            description = "Known-valid Android URI to open. Do not invent or guess URI schemes."
            required = true
        }
        config.rawJsonSchema(OPEN_URI_SCHEMA)
    }

    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
        val uri = try {
            parseUri(request.argumentsJson)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) {
                throw throwable
            }
            return BuiltinToolResult.failure(
                code = "INVALID_ARGUMENTS_JSON",
                message = "open_uri arguments must be a JSON object with a uri field.",
                hint = """Example: {"uri":"https://example.com"}""",
                fieldErrors = mapOf("argumentsJson" to (throwable.message ?: "Invalid JSON object.")),
            )
        }

        if (uri.isBlank()) {
            return BuiltinToolResult.failure(
                code = "MISSING_REQUIRED_FIELD",
                message = "open_uri requires a non-blank uri.",
                fieldErrors = mapOf("uri" to "Field 'uri' must not be blank."),
            )
        }

        val parsed = Uri.parse(uri)
        if (parsed.scheme.isNullOrBlank()) {
            return BuiltinToolResult.failure(
                code = "INVALID_URI",
                message = "URI must include a scheme.",
                hint = "Use a URI such as https://example.com, tel:10086, mailto:name@example.com, or geo:0,0.",
                data = JsonObject(mapOf("uri" to JsonPrimitive(uri))),
            )
        }

        val context = ContextProvider.await().applicationContext
        val intent = Intent(Intent.ACTION_VIEW, parsed).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            BuiltinToolResult.success(
                message = "URI opened.",
                data = JsonObject(mapOf("uri" to JsonPrimitive(uri))),
            )
        } catch (throwable: Throwable) {
            BuiltinToolResult.failure(
                code = "URI_OPEN_FAILED",
                message = throwable.message ?: "Failed to open URI.",
                hint = "Confirm the URI is valid and at least one installed app can handle it.",
                data = JsonObject(mapOf("uri" to JsonPrimitive(uri))),
            )
        }
    }

    private fun parseUri(argumentsJson: String): String {
        val element = try {
            Json.parseToJsonElement(argumentsJson)
        } catch (throwable: SerializationException) {
            throw IllegalArgumentException("argumentsJson is not valid JSON.", throwable)
        } catch (throwable: IllegalArgumentException) {
            throw IllegalArgumentException("argumentsJson is not valid JSON.", throwable)
        }
        val obj = element as? JsonObject
            ?: throw IllegalArgumentException("argumentsJson must be a JSON object.")
        return obj["uri"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
    }

    companion object {
        private const val OPEN_URI_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "uri": {
                  "type": "string",
                  "description": "Known-valid Android URI to open. Do not invent or guess URI schemes."
                }
              },
              "required": ["uri"]
            }
        """
    }
}
