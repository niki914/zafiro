package com.niki914.zafiro.chat.agentic.buildin.impl

import com.niki914.logging.Logger
import com.niki914.zafiro.chat.agentic.buildin.BuiltinTool
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRegistry
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolResult
import com.niki914.zafiro.chat.agentic.python.CustomPyToolHarness
import com.niki914.zafiro.chat.agentic.python.PyRuntime
import com.niki914.zafiro.chat.agentic.shell.ShellCommandSafetyPolicy
import com.niki914.zafiro.settings.RuntimeEnvironment
import com.niki914.zafiro.settings.model.RuntimeCustomPyTool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * py_meta_tools 元工具（用来创造工具的工具）：CustomPyTool 注册表的增删改查 + 试运行。
 *
 * - write 会先做签名反射（PyRuntime introspection）：提取 main 的基本类型标注
 *   与 docstring，作为 description/schemaJson 缓存进 store；失败即拒绝并回传
 *   可修复的错误信息（agent 自调试闭环的一半）。
 * - write 成功的条目由 LocalToolExecutor 回合内热注册（D20 同款机制）。
 * - test 用同一 harness 试跑草稿 code 或已存工具，write 前可先 test。
 */
class PyMetaToolsBuiltin(
    private val exec: suspend (code: String, timeoutMs: Long) -> String = PyRuntime::exec,
    private val safetyPolicy: ShellCommandSafetyPolicy = ShellCommandSafetyPolicy(),
    private val reservedNames: Set<String>? = null,
) : BuiltinTool() {

    override val name: String = "py_meta_tools"

    override val description: String = """
Meta-tool for creating Python tools: a tool used to build other tools. Each created
tool is a Python source defining `main(**kwargs)`; parameters come from basic type
annotations on main (str/int/float/bool), the docstring becomes the description.
Actions:
- list: show registered tools (name/description/schema/enabled)
- read: return full code of one tool
- write: create or replace a tool (validates syntax, main, and annotations)
- delete: remove a tool
- test: run draft `code` or an existing tool with sample `args` without saving
Store the result of a run by printing from main; stdout is returned.
    """.trimIndent()

    override val defaultEnabled: Boolean = true

    override val inputSchemaJson: String? get() = SCHEMA

    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
        val args = try {
            parseArguments(request.argumentsJson)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            return invalidArguments(throwable.message ?: "Invalid JSON.")
        }
        return try {
            when (args.action) {
                "list" -> list()
                "read" -> read(args.name)
                "write" -> write(args)
                "delete" -> delete(args.name)
                "test" -> test(args)
                else -> BuiltinToolResult.failure(
                    code = "UNKNOWN_ACTION",
                    message = "Unknown action '${args.action}'.",
                    hint = "Use one of: list, read, write, delete, test.",
                )
            }
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            Logger.w(LOG_TAG, "py_meta_tools ${args.action} failed: ${throwable.message}")
            BuiltinToolResult.failure(
                code = "PY_META_TOOLS_ERROR",
                message = throwable.message ?: "py_meta_tools action failed.",
            )
        }
    }

    // ── actions ──────────────────────────────────────────────────────────

    private suspend fun list(): BuiltinToolResult {
        val tools = RuntimeEnvironment.awaitSettingsGateway().listCustomPyTools()
        val items = tools.map { tool ->
            buildJsonObject {
                put("name", tool.name)
                put("description", tool.description)
                put("schema_json", tool.schemaJson)
                put("enabled", tool.enabled)
                put("timeout_ms", tool.timeoutMs)
            }
        }
        return BuiltinToolResult.success(
            message = "${items.size} custom py tool(s).",
            data = JsonObject(mapOf("tools" to JsonArray(items))),
        )
    }

    private suspend fun read(name: String?): BuiltinToolResult {
        val tool = requireTool(name) ?: return missingName()
        return BuiltinToolResult.success(
            message = "Tool ${tool.name}.",
            data = toolJson(tool, includeCode = true),
        )
    }

    private suspend fun write(args: ParsedArgs): BuiltinToolResult {
        val name = normalizeName(args.name)
            ?: return BuiltinToolResult.failure(
                code = "INVALID_NAME",
                message = "Field 'name' must match [a-z][a-z0-9_]* (a 'py_' prefix is added automatically).",
                fieldErrors = mapOf("name" to "invalid"),
            )
        val code = args.code?.trim().orEmpty()
        if (code.isBlank()) {
            return BuiltinToolResult.failure(
                code = "MISSING_CODE",
                message = "Field 'code' is required for action=write.",
                fieldErrors = mapOf("code" to "required"),
            )
        }
        if (name.removePrefix(PREFIX) in effectiveReservedNames()) {
            return BuiltinToolResult.failure(
                code = "RESERVED_NAME",
                message = "Name '$name' is reserved by a builtin tool.",
                fieldErrors = mapOf("name" to "reserved"),
            )
        }
        safetyPolicy.evaluate(code, toolName = name).takeIf { !it.allowed }?.let { decision ->
            return BuiltinToolResult.failure(
                code = "COMMAND_BLOCKED",
                message = decision.reason.ifBlank { "Code blocked by safety policy." },
            )
        }

        val introspection = introspect(code)
        introspection.error?.let { error ->
            return BuiltinToolResult.failure(
                code = error.code,
                message = error.message,
                hint = "Fix the code and retry; use action=test to debug draft code without saving.",
                fieldErrors = mapOf("code" to error.code.lowercase()),
            )
        }

        val gateway = RuntimeEnvironment.awaitSettingsGateway()
        val existing = gateway.listCustomPyTools().firstOrNull { it.name == name }
        val enabled = args.enabled ?: existing?.enabled ?: true
        val timeoutMs = (args.timeoutMs ?: existing?.timeoutMs
        ?: RuntimeCustomPyTool.DEFAULT_CUSTOM_PY_TOOL_TIMEOUT_MS)
            .coerceIn(1_000L, RuntimeCustomPyTool.MAX_CUSTOM_PY_TOOL_TIMEOUT_MS)

        val tool = RuntimeCustomPyTool(
            name = name,
            code = code,
            description = introspection.description.orEmpty(),
            schemaJson = introspection.schemaJson.orEmpty(),
            enabled = enabled,
            timeoutMs = timeoutMs,
        )
        gateway.saveCustomPyTool(tool, overwrite = true)?.let { validation ->
            return BuiltinToolResult.failure(
                code = "VALIDATION_FAILED",
                message = "${validation.field}: ${validation.message}",
                fieldErrors = mapOf(validation.field to validation.message),
            )
        }
        Logger.i(LOG_TAG, "py_meta_tools write ok name=$name timeoutMs=$timeoutMs")
        return BuiltinToolResult.success(
            message = if (existing == null) "Tool $name created." else "Tool $name updated.",
            hint = "The tool is callable now; run it with arguments matching its schema.",
            data = toolJson(tool, includeCode = false),
        )
    }

    private suspend fun delete(name: String?): BuiltinToolResult {
        val tool = requireTool(name) ?: return missingName()
        RuntimeEnvironment.awaitSettingsGateway().deleteCustomPyTool(tool.name)
        return BuiltinToolResult.success(message = "Tool ${tool.name} deleted.")
    }

    private suspend fun test(args: ParsedArgs): BuiltinToolResult {
        val draftCode = args.code?.trim().orEmpty()
        val code: String
        val timeoutMs: Long
        if (draftCode.isNotBlank()) {
            if (args.name.isNotBlank()) {
                return BuiltinToolResult.failure(
                    code = "AMBIGUOUS_TARGET",
                    message = "Provide either 'code' (draft test) or 'name' (existing tool), not both.",
                )
            }
            safetyPolicy.evaluate(draftCode, toolName = name).takeIf { !it.allowed }
                ?.let { decision ->
                    return BuiltinToolResult.failure(
                        code = "COMMAND_BLOCKED",
                        message = decision.reason.ifBlank { "Code blocked by safety policy." },
                    )
                }
            code = draftCode
            timeoutMs = (args.timeoutMs ?: RuntimeCustomPyTool.DEFAULT_CUSTOM_PY_TOOL_TIMEOUT_MS)
                .coerceIn(1_000L, RuntimeCustomPyTool.MAX_CUSTOM_PY_TOOL_TIMEOUT_MS)
        } else {
            val tool = requireTool(args.name) ?: return missingName()
            code = tool.code
            timeoutMs = (args.timeoutMs ?: tool.timeoutMs)
                .coerceIn(1_000L, RuntimeCustomPyTool.MAX_CUSTOM_PY_TOOL_TIMEOUT_MS)
        }

        return try {
            val output = exec(CustomPyToolHarness.buildRunner(code, args.argsJson), timeoutMs)
            BuiltinToolResult.success(
                message = "Test run finished.",
                data = buildJsonObject { put("stdout", output) },
            )
        } catch (e: TimeoutCancellationException) {
            BuiltinToolResult.failure(
                code = "TIMEOUT",
                message = "Test run timed out after ${timeoutMs / 1000}s.",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            BuiltinToolResult.failure(
                code = "PYTHON_ERROR",
                message = t.message ?: "Test run failed.",
            )
        }
    }

    // ── introspection ────────────────────────────────────────────────────

    private data class Introspection(
        val description: String?,
        val schemaJson: String?,
        val error: IntrospectionError?,
    )

    private data class IntrospectionError(val code: String, val message: String)

    private suspend fun introspect(code: String): Introspection {
        val output = try {
            exec(CustomPyToolHarness.buildIntrospection(code), INTROSPECTION_TIMEOUT_MS)
        } catch (e: TimeoutCancellationException) {
            return Introspection(
                null,
                null,
                IntrospectionError("INTROSPECTION_TIMEOUT", "Signature check timed out.")
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            return Introspection(
                null,
                null,
                IntrospectionError("INTROSPECTION_FAILED", t.message ?: "Signature check failed.")
            )
        }
        val json = try {
            Json.parseToJsonElement(output.trim()).jsonObject
        } catch (_: Exception) {
            return Introspection(
                null,
                null,
                IntrospectionError(
                    "INTROSPECTION_FAILED",
                    "Unexpected checker output: ${output.take(200)}"
                )
            )
        }
        if (json["error"] != null) {
            val code = json["error"]!!.jsonPrimitive.contentOrNull ?: "INVALID_CODE"
            val line = json["line"]?.jsonPrimitive?.longOrNull
            val message = buildString {
                append(json["message"]?.jsonPrimitive?.contentOrNull ?: "Invalid tool code.")
                if (line != null) append(" (line $line)")
            }
            return Introspection(null, null, IntrospectionError(code, message))
        }
        val description = json["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val schema = json["schema"]?.jsonObject
        return Introspection(description, schema?.toString().orEmpty(), null)
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private suspend fun requireTool(name: String?): RuntimeCustomPyTool? {
        val normalized = normalizeName(name) ?: return null
        return RuntimeEnvironment.awaitSettingsGateway().listCustomPyTools()
            .firstOrNull { it.name == normalized }
    }

    private fun missingName(): BuiltinToolResult = BuiltinToolResult.failure(
        code = "TOOL_NOT_FOUND",
        message = "No custom py tool found for the given 'name'. Use action=list to see registered tools.",
    )

    private fun normalizeName(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isBlank()) return null
        val prefixed = if (trimmed.startsWith(PREFIX)) trimmed else PREFIX + trimmed
        return prefixed.takeIf { NAME_PATTERN.matches(it) }
    }

    private fun toolJson(tool: RuntimeCustomPyTool, includeCode: Boolean): JsonObject {
        return buildJsonObject {
            put("name", tool.name)
            put("description", tool.description)
            put("schema_json", tool.schemaJson)
            put("enabled", tool.enabled)
            put("timeout_ms", tool.timeoutMs)
            if (includeCode) put("code", tool.code)
        }
    }

    // 惰性解析：companion 常量会在类初始化时触发 BuiltinToolRegistry.default()
    // → PyMetaToolsBuiltin() 的循环构造，必须延迟到调用点
    private fun effectiveReservedNames(): Set<String> {
        return reservedNames ?: BuiltinToolRegistry.default().all().map { it.name }.toSet()
    }

    private fun invalidArguments(message: String): BuiltinToolResult = BuiltinToolResult.failure(
        code = "INVALID_ARGUMENTS_JSON",
        message = "py_meta_tools arguments must be a JSON object with an 'action' field. ($message)",
    )

    private fun parseArguments(argumentsJson: String): ParsedArgs {
        val obj = try {
            Json.parseToJsonElement(argumentsJson.ifBlank { "{}" })
        } catch (e: Exception) {
            throw IllegalArgumentException("argumentsJson is not valid JSON.")
        }
        if (obj !is JsonObject) {
            throw IllegalArgumentException("argumentsJson must be a JSON object.")
        }
        fun str(key: String) = obj[key]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        return ParsedArgs(
            action = str("action"),
            name = str("name"),
            code = obj["code"]?.jsonPrimitive?.contentOrNull,
            argsJson = (obj["args"] as? JsonObject)?.toString().orEmpty(),
            enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull,
            timeoutMs = obj["timeout_ms"]?.jsonPrimitive?.longOrNull,
        )
    }

    private data class ParsedArgs(
        val action: String,
        val name: String,
        val code: String?,
        val argsJson: String,
        val enabled: Boolean?,
        val timeoutMs: Long?,
    )

    companion object {
        private const val LOG_TAG = "niki914_nexus_PyMetaToolsBuiltin"
        private const val PREFIX = "py_"
        private const val INTROSPECTION_TIMEOUT_MS = 60_000L
        private val NAME_PATTERN = Regex("^[a-z][a-z0-9_]{0,63}$")

        val SCHEMA = """
            {
              "type": "object",
              "properties": {
                "action": {
                  "type": "string",
                  "enum": ["list", "read", "write", "delete", "test"],
                  "description": "Registry operation to perform."
                },
                "name": {"type": "string", "description": "Tool name for read/write/delete/test. A py_ prefix is added automatically."},
                "code": {"type": "string", "description": "Python source defining main(**kwargs), for write; or draft code for test."},
                "args": {"type": "object", "description": "Sample arguments for action=test."},
                "enabled": {"type": "boolean", "description": "write only: enable/disable the tool (default keeps current value)."},
                "timeout_ms": {"type": "integer", "minimum": 1000, "maximum": 120000, "description": "Execution timeout (default 30000)."}
              },
              "required": ["action"]
            }
        """.trimIndent()
    }
}
