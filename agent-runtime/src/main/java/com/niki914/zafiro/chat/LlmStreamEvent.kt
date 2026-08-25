package com.niki914.zafiro.chat


sealed interface LlmStreamEvent {
    data object RoundStarted : LlmStreamEvent

    data class TextDelta(
        val delta: String,
        val fullText: String,
        val charsPerSecond: Float? = null,
    ) : LlmStreamEvent

    /** 思考块开始/进行中：id 为回合内由 Mapper 分配的单调块标识（OKIA index 跨轮复用，不可作身份），text 为当前全量。 */
    data class ThinkingStarted(
        val id: Int,
        val text: String,
    ) : LlmStreamEvent

    /** 思考块完成（含被掐中断）：id 同上，text 为最终全量；宿主据此把 [Thinking] 切成 [Thought]。 */
    data class ThinkingEnded(
        val id: Int,
        val text: String,
    ) : LlmStreamEvent

    data class ToolRunning(
        val call: ToolCallStatus,
    ) : LlmStreamEvent

    data class ToolSucceeded(
        val call: ToolCallStatus,
        val outputText: String? = null,
    ) : LlmStreamEvent

    data class ToolFailed(
        val call: ToolCallStatus,
        val message: String,
        val resultText: String? = null,
    ) : LlmStreamEvent

    data class Error(
        val message: String,
        val throwable: Throwable? = null,
        val code: LlmErrorCode? = null,
    ) : LlmStreamEvent

    /** 回合正常结束（纯终态标记；显示文本一律以 TextDelta 累积为准）。 */
    data object Completed : LlmStreamEvent
}

enum class LlmErrorCode {
    ConfigRequired,
    TurnConflict,
    Auth,
    Quota,
    RateLimit,
    Overloaded,
    Transport,
    Parse,
    RetryExhausted,
    HookFailed,
    ToolExecutionFailed,
}

data class ToolCallStatus(
    val callId: String? = null,
    val name: String,
    val label: String = name,
    val kind: ToolCallKind = ToolCallKind.Unknown,
    /** 工具调用参数原始 JSON（进程内透传，UI 侧按工具名提取摘要预览；宿主路径不使用）。 */
    val argumentsJson: String? = null,
)

enum class ToolCallKind {
    Local,
    Mcp,
    Unknown,
}
