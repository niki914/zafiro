package com.niki914.zafiro.runtime

import com.niki914.permission.Permission
import com.niki914.permission.PermissionState
import com.niki914.store.IpcWriteResult
import com.niki914.store.XIpcBridge
import com.niki914.xposed.api.util.ContextProvider
import com.niki914.zafiro.app.PermissionHolder
import com.niki914.zafiro.settings.RuntimeHostGateway

class IpcRuntimeHostGateway : RuntimeHostGateway {
    override suspend fun postNotification(
        title: String,
        content: String,
        uri: String?,
    ): Boolean {
        val context = ContextProvider.await()
        val pm = PermissionHolder.get(context)
        if (pm.status(Permission.NOTIFICATION) != PermissionState.GRANTED) {
            if (pm.request(Permission.NOTIFICATION).finalState != PermissionState.GRANTED) {
                return false
            }
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
