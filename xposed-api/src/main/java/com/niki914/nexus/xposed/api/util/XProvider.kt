package com.niki914.nexus.xposed.api.util

import kotlinx.coroutines.CompletableDeferred

abstract class XProvider<T> {

    private val contextDeferred = CompletableDeferred<T>()

    fun provide(t: T) = contextDeferred.complete(t)

    suspend fun await(): T {
        return contextDeferred.await()
    }
}