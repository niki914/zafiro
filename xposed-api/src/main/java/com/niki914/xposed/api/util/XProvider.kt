package com.niki914.xposed.api.util

import kotlinx.coroutines.CompletableDeferred

abstract class XProvider<T> {

    private val contextDeferred = CompletableDeferred<T>()

    fun provide(t: T) = contextDeferred.complete(t)

    suspend fun await(): T {
        return contextDeferred.await()
    }

    /** 非挂起快照读：未 provide 时返回 null，供无法 suspend 的路径（同步过滤器等）使用。 */
    fun awaitIfAvailable(): T? = if (contextDeferred.isCompleted) contextDeferred.getCompleted() else null
}