package com.niki914.okia.error

/**
 * 带稳定 code 的结构化错误。host 把 code 直接映射到 UI 文案，
 * 不解析异常字符串。status / delay 从响应解析，保持可空；
 * retryDelayMs 携带 Retry-After / retry-after-ms。
 * Design source: codex ApiError 分类、pi provider-retry，kai PRD §4.7。
 */
data class LLMError(
    val code: LLMErrorCode,
    val message: String,
    val cause: Throwable? = null,
    val statusCode: Int? = null,
    val retryDelayMs: Long? = null
) {

    val isRetryable: Boolean get() = code.isRetryable
}

/**
 * 稳定错误分类。quota / contextOverflow 永不重试；
 * rateLimit / overloaded / transport 可重试。
 * 未知工具不在此列：模型命名错误走 ToolCallOutcome.Failure 结果回喂
 * （RealAgentLoop.executeTools），模型可自纠，不产生回合级错误码。
 * Design source: kai PRD §4.7，对齐 pi 重试黑名单与 codex ApiError 变体。
 */
enum class LLMErrorCode(val isRetryable: Boolean) {
    Auth(false),
    Quota(false),
    RateLimit(true),
    Overloaded(true),
    ContextOverflow(false),
    Transport(true),
    Parse(false),
    HookFailed(false),
    ToolExecutionFailed(false),
    RetryExhausted(false)
}
