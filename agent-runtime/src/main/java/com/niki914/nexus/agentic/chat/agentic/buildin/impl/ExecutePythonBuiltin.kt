package com.niki914.nexus.agentic.chat.agentic.buildin.impl

import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.nexus.agentic.chat.agentic.buildin.TextResultBuiltinTool
import com.niki914.nexus.agentic.chat.agentic.buildin.TextToolResult
import com.niki914.nexus.agentic.chat.agentic.python.PyRuntime
import com.niki914.nexus.agentic.chat.agentic.shell.ShellCommandSafetyPolicy
import com.niki914.s3ss10n.LocalToolConfig
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

class ExecutePythonBuiltin(
    /**
     * Pluggable executor: [PyRuntime.exec] in production,
     * replaced with a test double in unit tests.
     */
    var executor: suspend (code: String, timeoutSec: Long) -> String = PyRuntime::exec,
    private val safetyPolicy: ShellCommandSafetyPolicy = ShellCommandSafetyPolicy(),
) : TextResultBuiltinTool() {

    override val name: String = "execute_python"

    override val description: String = """
Execute Python code in a sandboxed Python 3.11 runtime.
The environment includes requests, beautifulsoup4, and the full standard library.

Use this for:
- HTTP / REST API calls (GET, POST, PUT, DELETE via requests)
- Web scraping and HTML parsing (BeautifulSoup)
- Data processing (json, csv, re, math, datetime, collections)
- File I/O and path manipulation (open, os.path)
- Any computation or automation that needs Python libraries

How it works: Write a Python script and print your final result to stdout.
Standard library and pip packages are imported as usual (import requests, import json, etc.).

Limits: 30-second timeout, stdout/stderr returned as plain text.
Output is capped at 50 KB; print only the relevant result.
    """.trimIndent()

    override val defaultEnabled: Boolean = true

    override fun configure(config: LocalToolConfig) {
        config.description = description
        config.string("code") {
            description = "Python 3.11 source code to execute. Print final result to stdout."
            required = true
        }
        config.integer("timeout") {
            description = "Max seconds to wait (default 30, max 120)."
            required = false
        }
        config.rawJsonSchema(SCHEMA)
    }

    override suspend fun invokeText(request: BuiltinToolRequest): TextToolResult {
        val args = parseArgs(request.argumentsJson)
        return when (args) {
            is ParseResult.Success -> execute(args.code, args.timeoutSec)
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

    private suspend fun execute(code: String, timeoutSec: Long): TextToolResult {
        val decision = safetyPolicy.evaluate(code)
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
            val output = executor(code, timeoutSec)
            val capped = capOutput(output)
            TextToolResult.success(capped)
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            TextToolResult.failure(
                code = "TIMEOUT",
                message = "Python execution timed out after ${timeoutSec}s.",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            TextToolResult.failure(
                code = "PYTHON_ERROR",
                message = t.message ?: "Python execution failed.",
            )
        }
    }

    private fun capOutput(output: String, maxBytes: Int = 50_000): String {
        val bytes = output.encodeToByteArray()
        if (bytes.size <= maxBytes) return output
        val head = bytes.copyOf(maxBytes)
        val suffix = "\n\n[output truncated at $maxBytes bytes]".encodeToByteArray()
        return head.copyOf(maxBytes - suffix.size).decodeToString() +
                suffix.decodeToString()
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
        val timeoutSec = (obj["timeout"] as? JsonPrimitive)?.longOrNull
            ?.coerceIn(1, 120) ?: 30L
        return ParseResult.Success(code, timeoutSec)
    }

    private sealed interface ParseResult {
        data class Success(val code: String, val timeoutSec: Long) : ParseResult
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
    "timeout": {
      "type": "integer",
      "minimum": 1,
      "maximum": 120,
      "description": "Max seconds to wait (default 30)."
    }
  },
  "required": ["code"]
}
        """
    }
}
