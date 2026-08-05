package com.niki914.okai.tool

import com.niki914.okai.message.ToolCallOutcome

/**
 * Interceptor chain for tool calls, the core new capability of the kai redesign.
 * Interceptors run in registration order before the executor; any interceptor
 * may short-circuit with a terminal outcome.
 *
 * Design source: codex (openai/codex) approvals/hooks pipeline and OkHttp
 * interceptor chain, per kai PRD section 4.5.
 */
interface ToolCallInterceptor {

    suspend fun intercept(call: ToolCallContext, chain: ToolCallChain): ToolCallOutcome
}

/**
 * Chain passed to interceptors. proceed() enters the next interceptor or,
 * at the end, the tool executor.
 *
 * Design source: OkHttp Chain, per kai PRD section 4.5.
 */
interface ToolCallChain {

    suspend fun proceed(call: ToolCallContext): ToolCallOutcome
}
