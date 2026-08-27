package com.niki914.zafiro.settings.model

data class RuntimeLlmConfig(
    val provider: String = "",
    val endpoint: String = "",
    val apiKey: String = "",
    val model: String = "",
    /** LlmProtocol.wireId，如 "openai-responses"。空串回落默认协议。 */
    val protocol: String = "",
    val prompt: String = "",
    val proxy: String = "",
    val memoryPrompt: String = "",
    val memories: List<String> = emptyList(),
    val takeoverKeywords: List<String> = emptyList(),
)

enum class RuntimeAgentMemoryMode {
    Disabled,
    SharedMain,
}

data class RuntimeAgentProfile(
    val id: String,
    val name: String,
    val alias: String,
    val enabled: Boolean = true,
    val order: Int = 0,
    val memoryMode: RuntimeAgentMemoryMode = RuntimeAgentMemoryMode.SharedMain,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

data class RuntimeAgentValidation(
    val field: String,
    val message: String,
)

data class RuntimeSkillMetadata(
    val id: String,
    val name: String,
    val description: String,
    val relativePath: String,
    val absolutePath: String,
    val absoluteDir: String,
    val enabled: Boolean,
)

data class RuntimeLoadedSkill(
    val id: String,
    val name: String,
    val description: String,
    val relativePath: String,
    val absolutePath: String,
    val absoluteDir: String,
    val content: String,
    val enabled: Boolean,
)

data class RuntimeSkillValidation(
    val field: String,
    val message: String,
)

data class RuntimeMcpServer(
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val headers: Map<String, String> = emptyMap(),
)

/**
 * 持久化 Python 工具（pytools）条目。
 * code 是工具本体；description/schemaJson 是反射结果的缓存，代码变更后重算覆盖。
 */
data class RuntimePyTool(
    val name: String,
    val code: String,
    val description: String = "",
    val schemaJson: String = "",
    val enabled: Boolean = true,
    val timeoutMs: Long = DEFAULT_PY_TOOL_TIMEOUT_MS,
) {
    companion object {
        const val DEFAULT_PY_TOOL_TIMEOUT_MS: Long = 30_000L
        const val MAX_PY_TOOL_TIMEOUT_MS: Long = 120_000L
    }
}

data class RuntimeBuiltinToolSetting(
    val name: String,
    val description: String,
    val enabled: Boolean,
)

enum class RuntimeExecutionRuleEnabledMode {
    ALWAYS,
    LOCKED_ONLY,
    DISABLED,
}

data class RuntimeExecutionRule(
    val id: String,
    val name: String,
    val enabledMode: RuntimeExecutionRuleEnabledMode,
    val patterns: List<String>,
)

enum class RuntimeTakeoverTarget {
    NATIVE_ASSISTANT,
    ZAFIRO,
}

data class RuntimeTakeoverRule(
    val id: String,
    val name: String,
    val target: RuntimeTakeoverTarget,
    val enabled: Boolean = true,
    val patterns: List<String>,
)

data class RuntimeTakeoverSettings(
    val defaultTarget: RuntimeTakeoverTarget = RuntimeTakeoverTarget.ZAFIRO,
    val rules: List<RuntimeTakeoverRule> = emptyList(),
)

const val TAKEOVER_FIELD_NAME: String = "name"
const val TAKEOVER_FIELD_PATTERNS: String = "patterns"

data class RuntimeTakeoverRuleValidation(
    val field: String,
    val message: String,
)

data class RuntimeToolValidation(
    val field: String,
    val message: String,
)
