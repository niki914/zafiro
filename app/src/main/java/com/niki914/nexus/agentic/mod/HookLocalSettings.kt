package com.niki914.nexus.agentic.mod

import android.content.Context
import com.niki914.nexus.xposed.api.util.ContextProvider
import com.niki914.nexus.store.XIpcBridge

object HookLocalSettings {

    @Volatile
    private var cached = LocalSettings()

    suspend fun update(context: Context, client: XIpcBridge.StoreClient?): LocalSettings {
        return XService.getLocalSettings(context, client).also { cached = it }
    }

    suspend fun refreshFromHookContext(client: XIpcBridge.StoreClient?): LocalSettings {
        val context = ContextProvider.await()
        return update(context, client)
    }

    fun current(): LocalSettings = cached
}
