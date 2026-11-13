package com.niki914.zafiro.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/**
 * 引导还没走完时，导入必须等这三件启动写入结束，否则它们会盖掉刚写回去的数据。
 */
internal object StartupWrites {
    private val done = CompletableDeferred<Unit>()

    @Volatile
    private var tracking = false

    fun track(scope: CoroutineScope, vararg writes: suspend () -> Unit) {
        tracking = true
        val jobs = writes.map { block -> scope.launch { block() } }
        scope.launch {
            jobs.joinAll()
            if (!done.isCompleted) done.complete(Unit)
        }
    }

    suspend fun await() {
        if (!tracking) return
        done.await()
    }
}
