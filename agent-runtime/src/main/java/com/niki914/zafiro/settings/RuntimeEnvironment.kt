package com.niki914.zafiro.settings

import kotlinx.coroutines.CompletableDeferred

object RuntimeEnvironment {
    @Volatile
    private var bridge: RuntimeBridge? = null

    @Volatile
    private var bridgeReady = CompletableDeferred<RuntimeBridge>()

    fun install(bridge: RuntimeBridge) {
        this.bridge = bridge
        if (!bridgeReady.isCompleted) {
            bridgeReady.complete(bridge)
        }
    }

    fun requireBridge(): RuntimeBridge {
        return bridge
            ?: error("RuntimeBridge is not installed.")
    }

    suspend fun awaitBridge(): RuntimeBridge {
        bridge?.let { return it }
        return bridgeReady.await()
    }

    fun requireSettingsGateway(): RuntimeSettingsGateway {
        return requireBridge().settings
    }

    suspend fun awaitSettingsGateway(): RuntimeSettingsGateway {
        return awaitBridge().settings
    }

    fun clearForTest() {
        bridge = null
        bridgeReady = CompletableDeferred()
    }
}
