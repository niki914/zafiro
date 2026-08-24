package com.niki914.nexus.agentic.runtime.settings.model

data class RuntimeLlmConfig(
    val provider: String = "",
    val endpoint: String = "",
    val apiKey: String = "",
    val model: String = "",
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

data class RuntimeCustomTool(
    val name: String,
    val description: String,
    val command: String,
    val enabled: Boolean = true,
)

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
    NEXUS,
}

data class RuntimeTakeoverRule(
    val id: String,
    val name: String,
    val target: RuntimeTakeoverTarget,
    val enabled: Boolean = true,
    val patterns: List<String>,
)

data class RuntimeTakeoverSettings(
    val defaultTarget: RuntimeTakeoverTarget = RuntimeTakeoverTarget.NEXUS,
    val rules: List<RuntimeTakeoverRule> = emptyList(),
)

const val TAKEOVER_FIELD_NAME: String = "name"
const val TAKEOVER_FIELD_PATTERNS: String = "patterns"

data class RuntimeTakeoverRuleValidation(
    val field: String,
    val message: String,
)

data class RuntimeCustomToolValidation(
    val field: String,
    val message: String,
)
