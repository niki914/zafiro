package com.niki914.zafiro.settings.model

/**
 * 单次输出上限默认值（max_output_tokens / max_tokens）。
 * 旧默认是 okia 骨架里的 4096，会把长回答和带大参数的工具调用从中间切断
 * （截断的工具调用无法执行，是「工具块一直转圈」的根因）。
 */
const val DEFAULT_MAX_TOKENS: Int = 128_000

data class RuntimeLlmConfig(
    val provider: String = "",
    val endpoint: String = "",
    val apiKey: String = "",
    val model: String = "",
    /** LlmProtocol.wireId，如 "openai-responses"。空串回落默认协议。 */
    val protocol: String = "",
    /** 视觉模型开关（图片输入支持），默认关。 */
    val supportsImages: Boolean = false,
    val prompt: String = "",
    val proxy: String = "",
    val memoryPrompt: String = "",
    val memories: List<String> = emptyList(),
    val takeoverKeywords: List<String> = emptyList(),
    /** 流式空闲超时秒数；null = 不超时（Long.MAX_VALUE）。 */
    val idleTimeoutSeconds: Long? = 60L,
    /** 传输层自动重试次数。 */
    val retryMaxAttempts: Int = 3,
    /** 单次输出上限（max_output_tokens / max_tokens）。 */
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
    /** ThinkingLevel.wireValue；空串 = 不发送思考字段（Provider 默认行为）。 */
    val thinkingLevel: String = "",
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
 * 持久化 Python 工具（由 custom_py_tools 创建）条目。
 * code 是工具本体；description/schemaJson 是反射结果的缓存，代码变更后重算覆盖。
 */
data class RuntimeCustomPyTool(
    val name: String,
    val code: String,
    val description: String = "",
    val schemaJson: String = "",
    val enabled: Boolean = true,
    val timeoutMs: Long = DEFAULT_CUSTOM_PY_TOOL_TIMEOUT_MS,
) {
    companion object {
        const val DEFAULT_CUSTOM_PY_TOOL_TIMEOUT_MS: Long = 30_000L
        const val MAX_CUSTOM_PY_TOOL_TIMEOUT_MS: Long = 120_000L
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

    /** 命中后挂起等待用户确认；无确认渠道（宿主路径）时拒绝。 */
    CONFIRM,
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

/** 工具校验错误的来源：Code = 用户代码/配置问题，Internal = 应用内部问题。UI 据此选择提示文案。 */
enum class RuntimeToolValidationOrigin {
    Code,
    Internal,
}

data class RuntimeToolValidation(
    val field: String,
    val message: String,
    val origin: RuntimeToolValidationOrigin = RuntimeToolValidationOrigin.Code,
)

/**
 * Zafiro 作为 MCP Server 暴露给外部客户端（如桌面 Claude Code、Cursor）的配置。
 * 默认关闭服务，且仅在白名单内的安全工具默认开放；高危工具（terminal/python）默认关闭。
 */
data class RuntimeMcpHostConfig(
    val enabled: Boolean = false,
    val port: Int = DEFAULT_PORT,
    val host: String = DEFAULT_HOST,
    val bearerToken: String = "",
    val exposedTools: Set<String> = DEFAULT_EXPOSED_TOOLS,
) {
    fun isToolExposed(toolName: String): Boolean {
        if (toolName.startsWith("mcp_") || toolName.startsWith("mcp__")) {
            return false
        }
        return toolName in exposedTools
    }

    companion object {
        const val DEFAULT_PORT: Int = 8791
        const val DEFAULT_HOST: String = "127.0.0.1"

        val DEFAULT_EXPOSED_TOOLS: Set<String> = setOf(
            "screen_operation_accessibility",
            "screen_operation_shell",
            "screenshot",
            "launch_app",
            "find_installed_apps",
            "open_uri",
            "load_skill",
            "notify",
            "view_image",
            "memory",
        )
    }
}
