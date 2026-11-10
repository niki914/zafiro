package com.tricreta.zafiro.autonomy.run

import java.util.concurrent.ConcurrentHashMap

/** Runs executing in *this* process. Anything in-flight in the database but not here was orphaned by a process death. */
object ActiveRuns {
    private val ids = ConcurrentHashMap.newKeySet<Long>()

    fun add(id: Long) {
        ids.add(id)
    }

    fun remove(id: Long) {
        ids.remove(id)
    }

    fun ids(): Set<Long> = ids.toSet()
}
