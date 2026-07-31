package com.niki914.nexus.agentic.chat.agentic.stream

import com.niki914.nexus.agentic.chat.agentic.buildin.TextToolResult
import com.niki914.nexus.agentic.chat.agentic.buildin.TextToolResultCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses a JSON string into a [JsonObject], returning null on failure.
 * File-level helper used by [ParsedToolResult.decode].
 */
private fun parseJsonObject(value: String): JsonObject? {
    return runCatching { Json.parseToJsonElement(value) as? JsonObject }.getOrNull()
}

/**
 * Unified parsed representation of a tool result, combining text-protocol,
 * JSON-structured, and legacy text formats.
 */
data class ParsedToolResult(
    val status: TextToolResult.Status,
    val code: String?,
    val message: String?,
    val payload: String,
    val protocol: Protocol,
) {
    enum class Protocol { TextProtocol, JsonStructured, LegacyText }

    companion object {
        private val TEXT_RESULT_TOOL_NAMES = setOf(
            "load_skill",
            "screen_operation_accessibility",
            "screen_operation_shell",
        )

        /**
         * Unified decoder for all tool result formats.
         *
         * Resolution order:
         * 1. Text protocol — only if [toolName] is in [TEXT_RESULT_TOOL_NAMES].
         *    Delegates to [TextToolResultCodec.decode].
         * 2. JSON structured error (error.code / ok=false / non-zero exit_code).
         * 3. Legacy text — treated as success.
         */
        fun decode(raw: String?, toolName: String? = null): ParsedToolResult {
            val text = raw?.takeIf { it.isNotBlank() }
                ?: return ParsedToolResult(
                    status = TextToolResult.Status.Success, code = null, message = null,
                    payload = "", protocol = Protocol.LegacyText,
                )

            // Step 1: Text protocol — ONLY for whitelisted tools
            if (toolName in TEXT_RESULT_TOOL_NAMES) {
                val textResult = TextToolResultCodec.decode(text)
                if (textResult != null) {
                    return ParsedToolResult(
                        status = textResult.status,
                        code = textResult.code,
                        message = textResult.message,
                        payload = textResult.payload,
                        protocol = Protocol.TextProtocol,
                    )
                }
            }

            // Step 2: JSON structured error
            val json = parseJsonObject(text)
            if (json != null) {
                // Check for structured error: {"error": {"code": "...", "message": "..."}}
                val error = json["error"] as? JsonObject
                val errorCode = error?.get("code")
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                if (errorCode != null) {
                    val errorMessage = error["message"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.takeIf { it.isNotBlank() }
                    return ParsedToolResult(
                        status = TextToolResult.Status.Failure,
                        code = errorCode,
                        message = errorMessage ?: errorCode,
                        payload = text,
                        protocol = Protocol.JsonStructured,
                    )
                }

                // Check for ok=false
                val ok = json["ok"]?.jsonPrimitive?.booleanOrNull
                if (ok == false) {
                    val statusMsg = listOf("stderr", "message", "code")
                        .firstNotNullOfOrNull { key ->
                            json[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                        }
                    return ParsedToolResult(
                        status = TextToolResult.Status.Failure,
                        code = "OK_FALSE",
                        message = statusMsg ?: "Tool returned ok=false.",
                        payload = text,
                        protocol = Protocol.JsonStructured,
                    )
                }

                // Check for non-zero exit_code
                val exitCode = json["exit_code"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.toIntOrNull()
                if (exitCode != null && exitCode != 0) {
                    val exitMsg = listOf("stderr", "message", "code")
                        .firstNotNullOfOrNull { key ->
                            json[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                        }
                    return ParsedToolResult(
                        status = TextToolResult.Status.Failure,
                        code = "EXIT_CODE_$exitCode",
                        message = exitMsg ?: "Command completed with non-zero exit code $exitCode.",
                        payload = text,
                        protocol = Protocol.JsonStructured,
                    )
                }

                // JSON but no error indicators → success
                return ParsedToolResult(
                    status = TextToolResult.Status.Success, code = null, message = null,
                    payload = text, protocol = Protocol.JsonStructured,
                )
            }

            // Step 3: Legacy text/YAML → success
            return ParsedToolResult(
                status = TextToolResult.Status.Success, code = null, message = null,
                payload = text, protocol = Protocol.LegacyText,
            )
        }
    }
}

/**
 * Classifier that determines whether a tool result string represents a failure.
 *
 * The existing [failureMessage] method is preserved for backward compatibility.
 * It delegates to [ParsedToolResult.decode] without a [toolName] parameter,
 * matching the old behavior (JSON-only parsing, no text-protocol step).
 */
object LocalToolResultClassifier {
    /**
     * Returns a failure message if [resultJson] indicates a tool failure,
     * or null if the result is successful.
     *
     * This method preserves the original behavior: it only parses JSON-structured
     * results and does not attempt text-protocol parsing.
     */
    fun failureMessage(resultJson: String?): String? {
        val parsed = ParsedToolResult.decode(resultJson)
        return if (parsed.status == TextToolResult.Status.Failure) {
            parsed.message ?: parsed.code ?: "Tool failed."
        } else null
    }
}
