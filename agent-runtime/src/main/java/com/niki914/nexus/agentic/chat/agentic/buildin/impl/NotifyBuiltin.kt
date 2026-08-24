package com.niki914.nexus.agentic.chat.agentic.buildin.impl

import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinTool
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolResult
import com.niki914.nexus.agentic.runtime.settings.RuntimeEnvironment
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class NotifyBuiltin : BuiltinTool() {
    override val name: String = "notify"

    override val description: String =
        "Send an Android notification with title and content. The optional uri must be a known-valid Android uri that you are certain can be handled by the system; never invent, guess, or fabricate a uri. If uncertain, omit uri."

    override val defaultEnabled: Boolean = true

    override val inputSchemaJson: String? get() = NOTIFY_SCHEMA

    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
        val args = try {
            parseArguments(request.argumentsJson)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) {
                throw throwable
            }
            return BuiltinToolResult.failure(
                code = "INVALID_ARGUMENTS_JSON",
                message = "notify arguments must be a JSON object with title, content, and optional uri fields.",
                hint = """Example: {"title":"Reminder","content":"Call Alice at 5 PM","uri":"tel:10086"}""",
                fieldErrors = mapOf(
                    "argumentsJson" to (throwable.message ?: "Invalid JSON object.")
                ),
            )
        }

        val fieldErrors = mutableMapOf<String, String>()
        if (args.title.isBlank()) {
            fieldErrors["title"] = "Field 'title' must not be blank."
        }
        if (args.content.isBlank()) {
            fieldErrors["content"] = "Field 'content' must not be blank."
        }
        if (fieldErrors.isNotEmpty()) {
            return BuiltinToolResult.failure(
                code = "MISSING_REQUIRED_FIELD",
                message = "notify requires non-blank title and content.",
                hint = "Provide title and content. Omit uri unless it is a known-valid Android uri that is certain to work.",
                fieldErrors = fieldErrors,
            )
        }

        val data = buildPayload(args)
        val posted = RuntimeEnvironment.awaitBridge().host.postNotification(
            title = args.title,
            content = args.content,
            uri = args.uri,
        )

        return if (posted) {
            BuiltinToolResult.success(
                message = "Notification posted.",
                hint = "The Android notification bridge accepted the request.",
                data = data,
            )
        } else {
            BuiltinToolResult.failure(
                code = "NOTIFICATION_POST_FAILED",
                message = "Failed to post notification.",
                hint = "Check notification permissions, IPC bridge state, and whether the optional uri can be handled by Android.",
                data = data,
            )
        }
    }

    private fun parseArguments(argumentsJson: String): NotifyArguments {
        val element = try {
            Json.parseToJsonElement(argumentsJson)
        } catch (throwable: SerializationException) {
            throw IllegalArgumentException("argumentsJson is not valid JSON.", throwable)
        } catch (throwable: IllegalArgumentException) {
            throw IllegalArgumentException("argumentsJson is not valid JSON.", throwable)
        }
        val obj = element as? JsonObject
            ?: throw IllegalArgumentException("argumentsJson must be a JSON object.")
        return NotifyArguments(
            title = obj.string("title").trim(),
            content = obj.string("content").trim(),
            uri = obj.string("uri").trim().ifBlank { null },
        )
    }

    private fun buildPayload(args: NotifyArguments): JsonObject {
        val payload = linkedMapOf<String, JsonElement>(
            "title" to JsonPrimitive(args.title),
            "content" to JsonPrimitive(args.content),
        )
        args.uri?.let { payload["uri"] = JsonPrimitive(it) }
        return JsonObject(payload)
    }

    private fun JsonObject.string(key: String): String {
        return this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
    }

    private data class NotifyArguments(
        val title: String,
        val content: String,
        val uri: String?,
    )

    companion object {
        private const val NOTIFY_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "title": {
                  "type": "string",
                  "description": "Notification title. Must be a non-blank string."
                },
                "content": {
                  "type": "string",
                  "description": "Notification body text. Must be a non-blank string."
                },
                "uri": {
                  "type": "string",
                  "description": "Optional Android uri opened from the notification. Only provide this when you already know it is valid and usable; never invent or guess a uri."
                }
              },
              "required": ["title", "content"]
            }
        """
    }
}
