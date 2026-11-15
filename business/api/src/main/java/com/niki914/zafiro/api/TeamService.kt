package com.niki914.zafiro.api

/** Result of a team tool call: [text] is JSON or a short message the agent reads. */
data class TeamResult(val ok: Boolean, val text: String)

/**
 * The shared board, action log and examples library, installed as a service by the autonomy layer. The `board`, `engage`
 * and `examples` tools (agent-runtime) forward here because agent-runtime cannot see the autonomy database.
 * [tool] is board | engage | examples; every argument arrives as a string and is validated by the implementation.
 */
interface TeamService {
    suspend fun run(tool: String, args: Map<String, String>): TeamResult
}
