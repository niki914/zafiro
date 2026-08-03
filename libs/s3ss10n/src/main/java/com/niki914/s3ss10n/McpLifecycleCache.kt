package com.niki914.s3ss10n

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class McpLifecycleCache {
    private val states = mutableMapOf<String, Boolean>()
    private val mutex = Mutex()

    suspend fun isInitialized(fingerprint: String): Boolean = mutex.withLock {
        states[fingerprint] == true
    }

    suspend fun markInitialized(fingerprint: String) {
        mutex.withLock { states[fingerprint] = true }
    }

    suspend fun invalidate(fingerprint: String) {
        mutex.withLock { states.remove(fingerprint) }
    }
}
