package com.niki914.nexus.agentic.runtime

import com.niki914.nexus.agentic.runtime.settings.RuntimeHostGateway
import com.niki914.nexus.xposed.api.util.ContextProvider
import com.niki914.nexus.store.IpcWriteResult
import com.niki914.nexus.store.XIpcBridge

class IpcRuntimeHostGateway : RuntimeHostGateway {
    override suspend fun postNotification(
        title: String,
        content: String,
        uri: String?,
    ): Boolean {
        return XIpcBridge.postNotification(
            context = ContextProvider.await(),
            title = title,
            content = content,
            uri = uri,
            client = null,
        ) is IpcWriteResult.Success
    }
}
