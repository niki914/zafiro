package com.tricreta.zafiro.autonomy.run

import java.util.concurrent.ConcurrentHashMap

/** Lets the owner stop one running task from the UI. The executor registers a stop action per run and honours the request. */
object RunControl {
    private val stops = ConcurrentHashMap<Long, () -> Unit>()
    private val requested = ConcurrentHashMap.newKeySet<Long>()

    fun register(runId: Long, stop: () -> Unit) { stops[runId] = stop }

    fun unregister(runId: Long) { stops.remove(runId); requested.remove(runId) }

    /** @return false when that run is not running (any more). */
    fun requestStop(runId: Long): Boolean {
        val stop = stops[runId] ?: return false
        requested.add(runId)
        runCatching { stop() }
        return true
    }

    fun wasStopped(runId: Long): Boolean = runId in requested
}
