package com.niki914.zafiro.runtime

import com.niki914.zafiro.app.NotificationPermissionGate
import com.niki914.zafiro.settings.RuntimeHostGateway
import com.niki914.store.IpcWriteResult
import com.niki914.store.XIpcBridge
import com.niki914.xposed.api.util.ContextProvider

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
