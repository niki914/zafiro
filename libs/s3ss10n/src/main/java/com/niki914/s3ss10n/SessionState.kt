package com.niki914.s3ss10n

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class SessionState(
    initialConfig: SessionConfig
) {

    private val configMutex: Mutex = Mutex()
    private var config: SessionConfig = initialConfig
    private val roundMutex: Mutex = Mutex()
    private var currentRoundJob: Job? = null
    private var currentStopHook: (suspend (keepCurrentTurn: Boolean) -> Unit)? = null

    internal suspend fun currentConfig(): SessionConfig {
        return configMutex.withLock { config }
    }

    internal suspend fun updateConfig(
        block: SessionConfig.Builder.() -> Unit
    ): SessionConfig {
        return configMutex.withLock {
            val baseCodec = config.jsonCodec
            val baseEngine = config.httpEngine
            val updated = config.toBuilder().apply(block).build()
            if (updated.jsonCodec !== baseCodec) {
                xLog("X", "update ignored open-only field: jsonCodec")
                updated.jsonCodec = baseCodec
            }
            if (updated.httpEngine !== baseEngine) {
                xLog("X", "update ignored open-only field: httpEngine")
                updated.httpEngine = baseEngine
            }
            config = updated
            updated
        }
    }

    internal fun runReplacingCurrent(
        scope: CoroutineScope,
        cleanupTools: suspend () -> Unit,
        stopHook: suspend (keepCurrentTurn: Boolean) -> Unit,
        block: suspend () -> Unit
    ): Deferred<Unit> = scope.async {
        val roundJob = roundMutex.withLock {
            cancelCurrentRoundLocked(cleanupTools)
            scope.async {
                block()
            }.also {
                currentRoundJob = it
                currentStopHook = stopHook
            }
        }
        roundJob.await()
    }

    internal suspend fun stopCurrentRound(keepCurrentTurn: Boolean) {
        val activeRound = roundMutex.withLock {
            val job = currentRoundJob
            val stopHook = currentStopHook
            if (job == null || stopHook == null) {
                return
            }
            if (!job.isActive) {
                currentRoundJob = null
                currentStopHook = null
                return
            }
            ActiveRound(job = job, stopHook = stopHook)
        }

        activeRound.stopHook(keepCurrentTurn)
        if (activeRound.job != currentCoroutineContext()[Job]) {
            activeRound.job.join()
        }

        roundMutex.withLock {
            if (currentRoundJob == activeRound.job && !activeRound.job.isActive) {
                currentRoundJob = null
                currentStopHook = null
            }
        }
    }

    internal suspend fun cancelCurrentRound(
        cleanupTools: suspend () -> Unit
    ) {
        roundMutex.withLock {
            cancelCurrentRoundLocked(cleanupTools)
        }
    }

    internal suspend fun cancelCurrentRoundAndRun(
        cleanupTools: suspend () -> Unit,
        block: suspend () -> Unit
    ) {
        roundMutex.withLock {
            cancelCurrentRoundLocked(cleanupTools)
            block()
        }
    }

    private suspend fun cancelCurrentRoundLocked(
        cleanupTools: (suspend () -> Unit)?
    ) {
        currentRoundJob?.cancel()
        cleanupTools?.invoke()
        currentRoundJob?.join()
        currentRoundJob = null
        currentStopHook = null
    }

    private data class ActiveRound(
        val job: Job,
        val stopHook: suspend (keepCurrentTurn: Boolean) -> Unit
    )
}
