package com.niki914.nexus.agentic.chat.agentic

import com.niki914.logging.Logger
import com.niki914.nexus.agentic.chat.LocalTool
import com.niki914.nexus.agentic.chat.ResolvedTools
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolExecutor
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolResult
import com.niki914.nexus.agentic.chat.agentic.buildin.TextToolResult
import com.niki914.nexus.agentic.chat.agentic.buildin.TextToolResultCodec
import com.niki914.nexus.agentic.chat.agentic.custom.CustomToolExecutor
import com.niki914.okia.message.ToolCallOutcome
import com.niki914.okia.tooling.ToolCallContext
import com.niki914.okia.tooling.ToolExecutor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * OKIA ToolExecutor 适配：把 Nexus 本地工具（builtin + custom）的执行接到
 * OKIA 工具循环。执行永不抛异常，总是产出 ToolCallOutcome（§5.5 契约）：
 * - 结果 JSON（BuiltinToolResult / CustomToolExecutor 输出）按 "ok" 字段拆解
 *   Success / Failure；文本协议结果（TextResultBuiltinTool）经
 *   TextToolResultCodec 拆解
 * - onInterrupt：本地工具未被框架调用（okia §8.18 Q1），实现为 Interrupted
 * - create_custom_tool 成功且 enabled 时：注册进 inline 表并回调 host
 *   （D20 回合内注册：RealAgentLoop 每段现取 registry.snapshot()，同回合
 *   下一轮模型请求即可见新工具）
 * Design source: kai 时代 ToolCallDispatcher 执行逻辑 + okia ToolExecutor 契约。
 */
class LocalToolExecutor(
    private val builtinToolExecutor: BuiltinToolExecutor = BuiltinToolExecutor(),
    private val customToolExecutor: CustomToolExecutor = CustomToolExecutor(),
    private val currentTools: () -> ResolvedTools?,
    private val inlineCustomTools: MutableMap<String, LocalTool.Custom> = mutableMapOf(),
    private val onCustomToolCreated: suspend (LocalTool.Custom) -> Unit = {},
) : ToolExecutor {

    private companion object {
        const val LOG_TAG = "niki914_nexus_LocalToolExecutor"
        const val CREATE_CUSTOM_TOOL_NAME = "create_custom_tool"
        const val INVALID_NAME_PATTERN = """^[a-zA-Z_][a-zA-Z0-9_]{1,63}$"""
    }

    override suspend fun execute(call: ToolCallContext): ToolCallOutcome {
        val raw = executeLocal(name = call.name, argumentsJson = call.argumentsJson)
        if (call.name == CREATE_CUSTOM_TOOL_NAME) {
            registerInlineCustomIfCreated(call, raw)
        }
        return decodeOutcome(raw)
    }

    override fun onInterrupt(call: ToolCallContext): ToolCallOutcome =
        ToolCallOutcome.Interrupted()

    // ── 本地执行（builtin / custom 路由）───────────────────────────────────

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

        val customTool = tools
            ?.customTools
            .orEmpty()
            .filterIsInstance<LocalTool.Custom>()
            .firstOrNull { it.name == name }
            ?: inlineCustomTools[name]
        if (customTool != null) {
            return customToolExecutor.execute(customTool).also { result ->
                Logger.i(
                    LOG_TAG,
                    "local tool done name=$name kind=custom resultLength=${result.length} " +
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
            hint = "Check builtin_tool_flags or custom_tools configuration.",
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
            when (json["ok"]?.jsonPrimitive?.booleanOrNull) {
                true -> return ToolCallOutcome.Success(content = raw)
                false -> return ToolCallOutcome.Failure(
                    message = json["message"]?.jsonPrimitive?.contentOrNull
                        ?: "Tool failed.",
                    content = raw,
                )
                null -> Unit
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

    // ── create_custom_tool 回合内注册（D20）────────────────────────────────

    private suspend fun registerInlineCustomIfCreated(call: ToolCallContext, raw: String) {
        val result = try {
            Json.parseToJsonElement(raw).jsonObject
        } catch (_: Exception) {
            return
        }
        if (result["ok"]?.jsonPrimitive?.booleanOrNull != true) return
        val args = try {
            Json.parseToJsonElement(call.argumentsJson).jsonObject
        } catch (_: Exception) {
            return
        }
        val name = args["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val enabled = args["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
        if (!enabled) return
        if (!name.matches(Regex(INVALID_NAME_PATTERN))) return
        val tool = LocalTool.Custom(
            name = name,
            description = args["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            enabled = true,
            command = args["command"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
        inlineCustomTools[name] = tool
        // host 注册回调（LLMController → OkiaConfig.toolRegistry）
        try {
            onCustomToolCreated(tool)
        } catch (throwable: Throwable) {
            if (throwable is kotlinx.coroutines.CancellationException) throw throwable
            Logger.w(
                LOG_TAG,
                "onCustomToolCreated failed name=$name error=${throwable.message}"
            )
        }
    }
}