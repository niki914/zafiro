package com.niki914.zafiro.api

/** One agent profile as another agent sees it. */
data class DelegateAgentInfo(val name: String, val summary: String, val usesScreen: Boolean)

/**
 * [status] is DONE | NEEDS_HUMAN | FAILED (finished), RUNNING (background, not finished yet) or ERROR (could not start).
 * [handle] identifies a background call for later [AgentDelegator.result] calls.
 */
data class DelegateResult(val status: String, val text: String, val handle: String? = null) {
    val finished: Boolean get() = status != "RUNNING"
}

/**
 * Lets one agent call another by name (installed as a service by the autonomy layer; the tool lives in agent-runtime, which
 * cannot see autonomy). Background calls let agents work in parallel: one drives the screen while others work on data.
 */
interface AgentDelegator {
    suspend fun list(): List<DelegateAgentInfo>
    suspend fun call(agent: String, task: String, background: Boolean): DelegateResult
    suspend fun result(handle: String, waitMs: Long): DelegateResult
}
