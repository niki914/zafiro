package com.niki914.okai.session

import com.niki914.okai.message.Message

/**
 * Context window policy: triggers and performs compaction. The interface ships
 * now (M0); the host prompt layer implements it later, so the loop core stays
 * untouched when compaction arrives.
 *
 * Design source: kai PRD section 4.6 ContextPolicy; pi places compaction in
 * the coding-agent layer, never in the core loop.
 */
interface ContextPolicy {

    fun shouldCompact(history: List<Message>, budget: ContextBudget): Boolean

    suspend fun compact(history: List<Message>): List<Message>
}

/**
 * Token budget for one context window. estimation uses chars/4 plus usage
 * feedback from the last response.
 *
 * Design source: kai PRD section 4.6 context budget primitives.
 */
data class ContextBudget(
    val limitTokens: Long,
    val estimatedTokens: Long
) {

    val isOverLimit: Boolean get() = estimatedTokens >= limitTokens

    companion object {

        fun estimateTokens(text: String): Long = TODO()
    }
}
