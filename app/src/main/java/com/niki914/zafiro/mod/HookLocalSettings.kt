package com.niki914.zafiro.mod

import android.content.Context
import com.niki914.store.XIpcBridge
import com.niki914.xposed.api.util.ContextProvider

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
