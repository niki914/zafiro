package com.niki914.nexus.agentic.chat.agentic.buildin.impl

import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.nexus.agentic.chat.agentic.buildin.TextResultBuiltinTool
import com.niki914.nexus.agentic.chat.agentic.buildin.TextToolResult
import com.niki914.nexus.agentic.chat.agentic.python.PyRuntime
import com.niki914.nexus.agentic.chat.agentic.shell.ShellCommandSafetyPolicy
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
     *
     * @param code     Python source code to execute.
     * @param timeoutMs Max wait in milliseconds.
     */
    var executor: suspend (code: String, timeoutMs: Long) -> String = PyRuntime::exec,
    private val safetyPolicy: ShellCommandSafetyPolicy = ShellCommandSafetyPolicy(),
) : TextResultBuiltinTool() {

    override val name: String = "execute_python"

    override val description: String = """
Execute Python 3.11 code in an embedded runtime with requests, bs4, and
the full standard library.

## When to use

Use execute_python instead of terminal when:
- You need HTTP requests (Android shell has no curl/wget)
- You need to filter, parse, or transform output with regex, slicing, or JSON
- Multi-step logic with conditions, loops, or retries — only stdout and
  stderr enter the model context. Keep printed output concise and print
  only the final relevant result.
- You need to drive Android system actions from processed data (am, pm,
  input commands via os.popen or subprocess)
- A shell command may produce verbose output — use Python to extract just
  the relevant parts before they enter your context

Use terminal instead when:
- Single shell command with no processing needed
- You need session state — terminal holds a handle and preserves working
  directory and environment across calls. execute_python is stateless:
  each run starts fresh.

## Calling Android system commands

Use os.popen or subprocess to execute am, pm, input and other Android
commands. Prefix with su -c when root privileges are needed:

    # Regular commands (no root needed)
    os.popen("am start -a android.settings.WIFI_SETTINGS").read()

    # su -c: input tap / install / uninstall
    os.popen('su -c "input tap 500 800"').read()
    os.popen('su -c "pm install -r /sdcard/app.apk"').read()
    os.popen('su -c "pm uninstall com.example.app"').read()

    # su -c: open a file with a specific app
    # -p target package, -d file URI, -t MIME type
    os.popen('su -c "am start -p com.example.viewer -d file:///sdcard/Download/result.txt -t text/plain"').read()

Write files to public directories like /sdcard/Download so other apps
can access them via file:// URIs.

## Network requests and output control

    import requests

    # Extract only what you need -- don't print raw HTML or full JSON
    resp = requests.get("https://example.com/api/data", timeout=15)
    resp.raise_for_status()
    data = resp.json()
    for item in data["results"][:10]:
        print(item["id"], item["title"])

    # Scrape and extract with BeautifulSoup
    from bs4 import BeautifulSoup
    from urllib.parse import urljoin

    resp = requests.get(
        "https://example.com/search",
        params={"q": "topic"},
        timeout=15,
    )
    resp.raise_for_status()
    soup = BeautifulSoup(resp.text, "html.parser")
    for link in soup.select("h3 a")[:10]:
        title = link.get_text(" ", strip=True)
        url = urljoin(resp.url, link.get("href", ""))
        print(f"{title}\n  {url}")

    # Or extract readable plain text
    for tag in soup(["script", "style", "noscript"]):
        tag.decompose()
    text = "\n".join(soup.stripped_strings)
    print(text[:2000])

## Limits

- Timeout: 30 s default, 120 s max
- Output capped at 50 KB
- Treat every call as stateless — do not rely on variables, working
  directory, environment changes, open handles, or background tasks
  from earlier calls. Persist intentionally through files when needed.
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
            val output = executor(code, timeoutMs)
            val capped = capOutput(output)
            TextToolResult.success(capped)
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
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
