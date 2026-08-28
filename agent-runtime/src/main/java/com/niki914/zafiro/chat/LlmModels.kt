package com.niki914.zafiro.chat

import com.niki914.zafiro.chat.agentic.PromptComposeResult
import com.niki914.zafiro.chat.agentic.buildin.BuiltinTool

data class LlmRuntimeSnapshot(
    val config: ResolvedLlmConfig,
    val tools: ResolvedTools,
    val prompt: PromptComposeResult,
)

data class ResolvedLlmConfig(
    val endpoint: String,
    val apiKey: String,
    val model: String,
    val baseSystemPrompt: String,
    val finalSystemPrompt: String,
    val proxy: String = "",
)

data class ResolvedTools(
    val builtinTools: List<LocalTool> = emptyList(),
    val customPyTools: List<LocalTool> = emptyList(),
    val mcpServers: List<McpServerDefinition> = emptyList(),
)

sealed interface LocalTool {
    val name: String
    val description: String

    data class Builtin(
        override val name: String,
        override val description: String,
        val tool: BuiltinTool,
    ) : LocalTool

    /**
     * 持久化 Python 工具：code 在 ：python 进程执行，参数 schema 来自签名反射缓存。
     */
    data class Py(
        override val name: String,
        override val description: String,
        val code: String,
        val inputSchemaJson: String?,
        val timeoutMs: Long = 30_000L,
    ) : LocalTool
}

data class LocalToolParameter(
    val name: String,
    val description: String,
    val required: Boolean = false,
    val type: ToolParameterType = ToolParameterType.String,
)

enum class ToolParameterType {
    String,
    Int,
    Boolean,
    Number,
    Object,
    Array,
}

fun ResolvedTools.allLocalTools(): List<LocalTool> {
    return builtinTools + customPyTools
}

fun ResolvedTools.allLocalToolNames(): List<String> {
    return allLocalTools().map { it.name }
}

sealed interface McpServerDefinition {
    val name: String
    val enabled: Boolean

    data class Http(
        override val name: String,
        val url: String,
        override val enabled: Boolean = true,
        val headers: Map<String, String> = emptyMap(),
    ) : McpServerDefinition
}
