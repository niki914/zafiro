package com.niki914.nexus.agentic.runtime

import com.niki914.nexus.agentic.app.NotificationPermissionGate
import com.niki914.nexus.agentic.runtime.settings.RuntimeHostGateway
import com.niki914.nexus.store.IpcWriteResult
import com.niki914.nexus.store.XIpcBridge
import com.niki914.nexus.xposed.api.util.ContextProvider

class IpcRuntimeHostGateway : RuntimeHostGateway {
    override suspend fun postNotification(
        title: String,
        content: String,
        uri: String?,
    ): Boolean {
        val context = ContextProvider.await()
        if (!NotificationPermissionGate.isGranted(context)) {
            NotificationPermissionGate.requestIfNeeded(context)
            return false
        }
        return XIpcBridge.postNotification(
            context = context,
            title = title,
            content = content,
            uri = uri,
            client = null,
        ) is IpcWriteResult.Success
    }
}
