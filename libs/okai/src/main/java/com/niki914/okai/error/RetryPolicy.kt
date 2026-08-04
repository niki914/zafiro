package com.niki914.okai.error

/**
 * Backoff parameters shared by transport-level and turn-level retry.
 * Delay formula: min(baseDelayMs * 2^(attempt-1), maxDelayMs) plus jitter.
 *
 * Design source: pi provider-retry.ts and codex retry.rs backoff, per kai PRD section 4.7.
 */
data class RetryPolicy(
    val maxAttempts: Int = 3,
    val baseDelayMs: Long = 500,
    val maxDelayMs: Long = 60_000,
    val jitterRatio: Float = 0.1f
)
