package com.niki914.okia.error

import kotlin.random.Random

/**
 * 传输级与回合级重试共享的退避参数。
 * 延迟公式：min(baseDelayMs * 2^(attempt-1), maxDelayMs) 乘以
 * (1 ± jitterRatio) 抖动（乘法抖动，对齐 codex backoff 0.9~1.1）。
 * Design source: pi provider-retry.ts、codex retry.rs 退避，kai PRD §4.7。
 */
data class RetryPolicy(
    val maxAttempts: Int = 3,
    val baseDelayMs: Long = 500,
    val maxDelayMs: Long = 60_000,
    val jitterRatio: Float = 0.1f
) {

    // 第 attempt 次重试（1 起）的退避延迟。指数上溢防护：2^n 封顶 2^30。
    fun delayMs(attempt: Int): Long {
        val exp = 1L shl (attempt - 1).coerceAtMost(30)
        val raw = (baseDelayMs * exp).coerceAtMost(maxDelayMs)
        val factor = 1.0 + (Random.nextDouble() * 2.0 - 1.0) * jitterRatio
        return (raw * factor).toLong()
    }
}
