package com.niki914.zafiro.api.model

/**
 * Cumulative token usage of one agent, summed over every model request it made.
 *
 * [billable] deliberately excludes cache reads: they are discounted, and counting them would make long tool loops
 * (whose prompt grows every step) hit a budget that real cost never approached.
 */
data class TokenUsage(
    val requests: Int = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
) {
    val billable: Long get() = inputTokens + outputTokens + cacheWriteTokens
}
