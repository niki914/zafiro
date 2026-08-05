package com.niki914.okai.runtime

/**
 * Terminates host-managed tool resources when a turn is cancelled.
 * Coroutine cancellation alone cannot kill child processes or sessions,
 * so hosts implement this with their own resource layer.
 *
 * Design source: independent design; validated in the Nexus stop handling
 * (PyRuntime.kill / TerminalSessionPool.closeAll).
 */
interface ForceStopHook {

    suspend fun onForceStop()
}
