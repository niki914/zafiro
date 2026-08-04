package com.niki914.s3ss10n.util

import com.niki914.s3ss10n.Message
import com.niki914.s3ss10n.ToolCallSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class ToolCallWaiter<CTX>(
    private val scope: CoroutineScope,
    private val onToolCall: suspend (ToolCallSpec, CTX) -> Message.Tool
) {
    private val currCalls = mutableListOf<Deferred<Pair<ToolCallSpec, Message.Tool>>>()
    private val mutex = Mutex()

    suspend fun isEmpty(): Boolean = mutex.withLock {
        currCalls.isEmpty()
    }

    suspend fun enqueue(toolCall: ToolCallSpec, ctx: CTX) {
        val deferred = scope.async(start = CoroutineStart.LAZY) {
            toolCall to onToolCall(toolCall, ctx)
        }
        mutex.withLock {
            currCalls.add(deferred)
        }
        deferred.start()
    }

    suspend fun awaitAll(): List<Pair<ToolCallSpec, Message.Tool>> {
        val calls = mutex.withLock {
            currCalls.toList()
        }
        return try {
            calls.awaitAll()
        } finally {
            if (calls.all { it.isCompleted }) {
                mutex.withLock {
                    currCalls.removeAll(calls.toSet())
                }
            }
        }
    }

    suspend fun cancelAndClear(join: Boolean = false) {
        val currentJob = currentCoroutineContext()[Job]
        val calls = mutex.withLock {
            currCalls.toList().also {
                currCalls.clear()
            }
        }
        calls.forEach {
            it.cancel()
            if (join && it != currentJob) {
                it.join()
            }
        }
    }
}
