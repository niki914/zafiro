package com.niki914.okai.runtime

/**
 * Terminates host-managed tool resources when a turn is cancelled.
 * Coroutine cancellation alone cannot kill child processes or sessions,
 * so hosts implement this with their own resource layer.
 *
 * Contract: called at most once per cancelled turn, inside the loop's
 * NonCancellable cleanup and before interrupted outcomes are assembled,
 * so kills land first (matching the Nexus kill-then-stop order). The call
 * is awaited; a throwing hook is caught and does not abort the cleanup,
 * because its purpose is best-effort resource reclamation.
 *
 * Design source: independent design; validated in the Nexus stop handling
 * (PyRuntime.kill / TerminalSessionPool.closeAll).
 */
interface ForceStopHook {

    suspend fun onForceStop()
}
