package com.niki914.zafiro.chat.agentic.device

import com.niki914.xposed.api.util.ContextProvider
import com.niki914.xposed.api.util.XProvider

object AppInfoProvider : XProvider<AppInfoCache>() {
    @Volatile
    private var installed = false

    suspend fun cache(): AppInfoCache {
        if (!installed) {
            val context = ContextProvider.await().applicationContext
            installed = provide(AppInfoCache(context)) || installed
        }
        return await()
    }
}
