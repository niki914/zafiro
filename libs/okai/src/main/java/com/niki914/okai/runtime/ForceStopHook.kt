package com.niki914.okai.runtime

import com.niki914.okai.message.ContentBlock

/**
 * Terminates host-managed tool resources when a turn is cancelled.
 * Coroutine cancellation alone cannot kill child processes or sessions,
 * so hosts implement this with their own resource layer.
 *
 * Contract: called by the Okai coordinator before the turn job is
 * cancelled. Killing first unblocks tool calls that do not respond to
 * coroutine cancellation, so a stop never waits on a blocked tool
 * forever. Called at most once per cancelled turn; calls scopes the
 * termination to this turn's dispatched tool calls, so hosts sharing a
 * global resource pool do not kill other sessions' tools. The call is
 * awaited; a throwing hook is caught and does not abort the stop,
 * because its purpose is best-effort resource reclamation.
 *
 * Design source: independent design; kill-then-stop order validated in
 * the Nexus stop handling (PyRuntime.kill / TerminalSessionPool.closeAll
 * run before the kai stop call).
 */
interface ForceStopHook {

    suspend fun onForceStop(calls: List<ContentBlock.ToolCall>)
}
