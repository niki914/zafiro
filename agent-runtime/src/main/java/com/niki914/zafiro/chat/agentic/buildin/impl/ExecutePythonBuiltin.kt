package com.niki914.zafiro.chat.agentic.buildin.impl

import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.TextResultBuiltinTool
import com.niki914.zafiro.chat.agentic.buildin.TextToolResult
import com.niki914.zafiro.chat.agentic.python.PyRuntime
import com.niki914.zafiro.chat.agentic.shell.ShellCommandSafetyPolicy
import com.niki914.zafiro.util.ToolOutputTruncator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

class ExecutePythonBuiltin(
    /**
     * Pluggable executor: [PyRuntime.exec] in production,
     * replaced with a test double in unit tests.
     *
     * @param code     Python source code to execute.
     * @param timeoutMs Max wait in milliseconds.
     */
    var executor: suspend (code: String, timeoutMs: Long) -> String = PyRuntime::exec,
    private val safetyPolicy: ShellCommandSafetyPolicy = ShellCommandSafetyPolicy(),
) : TextResultBuiltinTool() {

    override val name: String = "execute_python"

    override val description: String = """
Execute Python code in an Android environment with the full standard library plus requests and bs4.
The Android shell has no curl/wget — use this tool for HTTP requests.
Can drive Android system commands (am, pm, input) via os.popen or subprocess; prefix with su -c when root is needed.
Write files to public directories like /sdcard/Download so other apps can access them via file:// URIs.

State does not persist between calls: every run starts fresh — no variables, working directory, environment
changes, open handles, or background tasks. Persist intentionally through files when needed.

Limits: timeout 30 s default, 120 s max; output capped at 50 KB.
    """.trimIndent()

    override val defaultEnabled: Boolean = true

    override val inputSchemaJson: String? get() = SCHEMA

    override suspend fun invokeText(request: BuiltinToolRequest): TextToolResult {
        val args = parseArgs(request.argumentsJson)
        return when (args) {
            is ParseResult.Success -> execute(args.code, args.timeoutMs)
            is ParseResult.InvalidJson -> TextToolResult.failure(
                code = "INVALID_ARGUMENTS_JSON",
                message = args.message,
            )

            is ParseResult.MissingCode -> TextToolResult.failure(
                code = "MISSING_CODE",
                message = "Field 'code' is required.",
            )
        }
    }

    private suspend fun execute(code: String, timeoutMs: Long): TextToolResult {
        val decision = safetyPolicy.evaluate(code, toolName = name)
        if (!decision.allowed) {
            return TextToolResult.failure(
                code = "COMMAND_BLOCKED",
                message = buildString {
                    append(decision.reason.ifBlank { "Code blocked by safety policy." })
                    decision.matchedRuleId?.let { append("\nmatched_rule_id: $it") }
                    decision.matchedRuleName?.let { append("\nmatched_rule_name: $it") }
                    decision.matchedPattern?.let { append("\nmatched_pattern: $it") }
                },
            )
        }
        return try {
            val output = executor(code, timeoutMs)
            val capped = capOutput(output)
            TextToolResult.success(capped)
        } catch (e: TimeoutCancellationException) {
            TextToolResult.failure(
                code = "TIMEOUT",
                message = "Python execution timed out after ${timeoutMs / 1000}s.",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            val msg = t.message ?: "Python execution failed."
            val isTimeout = msg.contains("timed out after")
            TextToolResult.failure(
                code = if (isTimeout) "TIMEOUT" else "PYTHON_ERROR",
                message = msg,
            )
        }
    }

    private fun capOutput(
        output: String,
        maxBytes: Int = ToolOutputTruncator.DEFAULT_MAX_BYTES
    ): String {
        val truncation = ToolOutputTruncator.truncateTail(output, maxBytes = maxBytes)
        if (!truncation.truncated) return output
        return truncation.content + "\n\n[Output truncated: showing last " +
                truncation.content.count { it == '\n' } + " of " + truncation.totalLines +
                " lines]"
    }

    private fun parseArgs(argumentsJson: String): ParseResult {
        val obj = try {
            Json.parseToJsonElement(argumentsJson.ifBlank { "{}" })
        } catch (e: SerializationException) {
            return ParseResult.InvalidJson("argumentsJson is not valid JSON.")
        } catch (e: IllegalArgumentException) {
            return ParseResult.InvalidJson("argumentsJson is not valid JSON.")
        }
        if (obj !is JsonObject) {
            return ParseResult.InvalidJson("argumentsJson must be a JSON object.")
        }
        val code = (obj["code"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?: return ParseResult.MissingCode
        val timeoutMs = (obj["timeout_ms"] as? JsonPrimitive)?.longOrNull
            ?.coerceIn(1000, 120_000) ?: 30_000L
        return ParseResult.Success(code, timeoutMs)
    }

    private sealed interface ParseResult {
        data class Success(val code: String, val timeoutMs: Long) : ParseResult
        data class InvalidJson(val message: String) : ParseResult
        data object MissingCode : ParseResult
    }

    companion object {
        private const val SCHEMA = """
{
  "type": "object",
  "properties": {
    "code": {
      "type": "string",
      "description": "Python 3.11 source code to execute. Print final result to stdout."
    },
    "timeout_ms": {
      "type": "integer",
      "minimum": 1000,
      "maximum": 120000,
      "description": "Max wait in milliseconds (default 30000)."
    }
  },
  "required": ["code"]
}
        """
    }
}
