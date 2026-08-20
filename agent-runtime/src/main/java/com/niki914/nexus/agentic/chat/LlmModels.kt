package com.niki914.nexus.agentic.chat

import com.niki914.nexus.agentic.chat.agentic.PromptComposeResult
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinTool

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
    val customTools: List<LocalTool> = emptyList(),
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

    data class Custom(
        override val name: String,
        override val description: String,
        val enabled: Boolean,
        val command: String,
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
    return builtinTools + customTools
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
