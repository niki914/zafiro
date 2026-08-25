package com.niki914.zafiro.chat


sealed interface LlmStreamEvent {
    data object RoundStarted : LlmStreamEvent

    data class TextDelta(
        val delta: String,
        val fullText: String,
        val charsPerSecond: Float? = null,
    ) : LlmStreamEvent

    /** 思考块开始/进行中：text 为当前全量（OKIA Delta 也映射为它），单独累积渲染不混入正文。 */
    data class ThinkingStarted(
        val text: String,
    ) : LlmStreamEvent

    /** 思考块完成（含被掐中断）：text 为最终全量；宿主据此把 [Thinking] 切成 [Thought]。 */
    data class ThinkingEnded(
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
)

enum class ToolCallKind {
    Local,
    Mcp,
    Unknown,
}
