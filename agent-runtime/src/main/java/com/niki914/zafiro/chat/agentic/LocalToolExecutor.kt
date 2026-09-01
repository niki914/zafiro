package com.niki914.zafiro.chat.agentic

import com.niki914.logging.Logger
import com.niki914.okia.message.ToolCallOutcome
import com.niki914.okia.tooling.ToolCallContext
import com.niki914.okia.tooling.ToolExecutor
import com.niki914.zafiro.chat.LocalTool
import com.niki914.zafiro.chat.ResolvedTools
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolExecutor
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolResult
import com.niki914.zafiro.chat.agentic.buildin.TextToolResult
import com.niki914.zafiro.chat.agentic.buildin.TextToolResultCodec
import com.niki914.zafiro.chat.agentic.python.CustomPyToolExecutor
import com.niki914.zafiro.chat.agentic.stream.LocalToolResultClassifier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * OKIA ToolExecutor 适配：把 Zafiro 本地工具（builtin + py）的执行接到
 * OKIA 工具循环。执行永不抛异常，总是产出 ToolCallOutcome（§5.5 契约）：
 * - 结果 JSON（BuiltinToolResult / CustomPyToolExecutor 输出）按 "ok" 字段拆解
 *   Success / Failure；文本协议结果（TextResultBuiltinTool）经
 *   TextToolResultCodec 拆解
 * - onInterrupt：本地工具未被框架调用（okia §8.18 Q1），实现为 Interrupted
 * - py_meta_tools write 成功且 enabled 时：注册进 inline 表并回调 host
 *   （D20 回合内注册：RealAgentLoop 每段现取 registry.snapshot()，同回合
 *   下一轮模型请求即可见新工具）
 * Design source: okia ToolExecutor 契约。
 */
class LocalToolExecutor(
    private val builtinToolExecutor: BuiltinToolExecutor = BuiltinToolExecutor(),
    private val customPyToolExecutor: CustomPyToolExecutor = CustomPyToolExecutor(),
    private val currentTools: () -> ResolvedTools?,
    private val inlineCustomPyTools: MutableMap<String, LocalTool.Py> = mutableMapOf(),
    private val onCustomPyToolWritten: suspend (LocalTool.Py) -> Unit = {},
) : ToolExecutor {

    private companion object {
        const val LOG_TAG = "niki914_nexus_LocalToolExecutor"
        const val PY_META_TOOLS_NAME = "py_meta_tools"
        const val CUSTOM_PY_TOOL_NAME_PATTERN = """^[a-z][a-z0-9_]{0,63}$"""
    }

    override suspend fun execute(call: ToolCallContext): ToolCallOutcome {
        val raw = executeLocal(name = call.name, argumentsJson = call.argumentsJson)
        if (call.name == PY_META_TOOLS_NAME) {
            registerInlinePyIfWritten(call, raw)
        }
        return decodeOutcome(raw)
    }

    override fun onInterrupt(call: ToolCallContext): ToolCallOutcome =
        ToolCallOutcome.Interrupted()

    // ── 本地执行（builtin / py 路由）──────────────────────────────────

    private suspend fun executeLocal(name: String, argumentsJson: String): String {
        val startedAtMs = System.currentTimeMillis()
        Logger.i(
            LOG_TAG,
            "local tool start name=$name argsLength=${argumentsJson.length}"
        )
        val tools = currentTools()
        val builtinTool = tools
            ?.builtinTools
            .orEmpty()
            .filterIsInstance<LocalTool.Builtin>()
            .firstOrNull { it.name == name }
        if (builtinTool != null) {
            return builtinToolExecutor.execute(
                tool = builtinTool.tool,
                argumentsJson = argumentsJson,
            ).also { result ->
                Logger.i(
                    LOG_TAG,
                    "local tool done name=$name kind=builtin resultLength=${result.length} " +
                            "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
                )
            }
        }

        val customPyTool = tools
            ?.customPyTools
            .orEmpty()
            .filterIsInstance<LocalTool.Py>()
            .firstOrNull { it.name == name }
            ?: inlineCustomPyTools[name]
        if (customPyTool != null) {
            return customPyToolExecutor.execute(customPyTool, argumentsJson).also { result ->
                Logger.i(
                    LOG_TAG,
                    "local tool done name=$name kind=py resultLength=${result.length} " +
                            "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
                )
            }
        }

        Logger.w(
            LOG_TAG,
            "local tool not executable name=$name " +
                    "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
        )
        return BuiltinToolResult.failure(
            code = "LOCAL_TOOL_NOT_EXECUTABLE",
            message = "Local tool '$name' is not executable in current runtime.",
            hint = "Check builtin_tool_flags or py_meta_tools configuration.",
        ).toJsonString()
    }

    // ── 结果 → ToolCallOutcome 拆解 ────────────────────────────────────────

    private fun decodeOutcome(raw: String): ToolCallOutcome {
        val json = try {
            Json.parseToJsonElement(raw).jsonObject
        } catch (_: Exception) {
            null
        }
        if (json != null) {
            // JSON 结构化错误判定复用统一解码器（error.code / ok=false / 非零
            // exit_code，覆盖 Hermes 风格 terminal 结果）；无错误标记 = 成功
            val failure = LocalToolResultClassifier.failureMessage(raw)
            return if (failure != null) {
                ToolCallOutcome.Failure(message = failure, content = raw)
            } else {
                ToolCallOutcome.Success(content = raw)
            }
        }
        // 非 JSON：文本协议工具结果（#!tool-result 头）
        val text = TextToolResultCodec.decode(raw)
        if (text != null) {
            return when (text.status) {
                TextToolResult.Status.Success -> ToolCallOutcome.Success(content = raw)
                TextToolResult.Status.Failure -> ToolCallOutcome.Failure(
                    message = text.message ?: text.code ?: "Tool failed.",
                    content = raw,
                )
            }
        }
        // 未知格式：保守成功，原文回喂模型（沿用旧运行时行为）
        return ToolCallOutcome.Success(content = raw)
    }

    // ── py_meta_tools write 回合内注册（D20）────────────────────────────────

    private suspend fun registerInlinePyIfWritten(call: ToolCallContext, raw: String) {
        val result = try {
            Json.parseToJsonElement(raw).jsonObject
        } catch (_: Exception) {
            return
        }
        if (result["ok"]?.jsonPrimitive?.booleanOrNull != true) return
        val data = result["data"] as? JsonObject ?: return
        val args = try {
            Json.parseToJsonElement(call.argumentsJson).jsonObject
        } catch (_: Exception) {
            return
        }
        if (args["action"]?.jsonPrimitive?.contentOrNull != "write") return
        val name = data["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (!name.matches(Regex(CUSTOM_PY_TOOL_NAME_PATTERN))) return
        val code = args["code"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (code.isBlank()) return
        val enabled = data["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
        if (!enabled) {
            inlineCustomPyTools.remove(name)
            return
        }
        val tool = LocalTool.Py(
            name = name,
            description = data["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            code = code,
            inputSchemaJson = data["schema_json"]?.jsonPrimitive?.contentOrNull,
            timeoutMs = data["timeout_ms"]?.jsonPrimitive?.longOrNull ?: 30_000L,
        )
        inlineCustomPyTools[name] = tool
        // host 注册回调（LLMController → OkiaConfig.toolRegistry）
        try {
            onCustomPyToolWritten(tool)
        } catch (throwable: Throwable) {
            if (throwable is kotlinx.coroutines.CancellationException) throw throwable
            Logger.w(
                LOG_TAG,
                "onCustomPyToolWritten failed name=$name error=${throwable.message}"
            )
        }
    }
}