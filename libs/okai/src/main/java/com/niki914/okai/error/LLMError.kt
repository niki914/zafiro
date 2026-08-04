package com.niki914.okai.error

/**
 * Structured error with a stable code. Host maps codes to UI copy directly and
 * never parses exception strings. Status and delay are parsed from responses
 * and stay nullable; retryDelayMs carries Retry-After / retry-after-ms.
 *
 * Design source: codex ApiError taxonomy (openai/codex) and pi provider-retry,
 * per kai PRD section 4.7 LLMError.Code.
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
 * Stable error taxonomy. quota and contextOverflow are never retried;
 * rateLimit, overloaded and transport are.
 *
 * Design source: kai PRD section 4.7, aligned with pi retry blacklist
 * (insufficient_quota, billing) and codex ApiError variants.
 */
enum class LLMErrorCode(val isRetryable: Boolean) {
    Auth(false),
    Quota(false),
    RateLimit(true),
    Overloaded(true),
    ContextOverflow(false),
    Transport(true),
    Parse(false),
    RetryExhausted(false)
}
