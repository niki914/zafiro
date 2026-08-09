package com.niki914.okia.error

/**
 * 传输级与回合级重试共享的退避参数。
 * 延迟公式：min(baseDelayMs * 2^(attempt-1), maxDelayMs) 加抖动。
 * Design source: pi provider-retry.ts、codex retry.rs 退避，kai PRD §4.7。
 */
data class RetryPolicy(
    val maxAttempts: Int = 3,
    val baseDelayMs: Long = 500,
    val maxDelayMs: Long = 60_000,
    val jitterRatio: Float = 0.1f
)
