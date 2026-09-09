package com.niki914.zafiro.runtime

import com.niki914.permission.Permission
import com.niki914.permission.PermissionState
import com.niki914.store.IpcWriteResult
import com.niki914.store.XIpcBridge
import com.niki914.xposed.api.util.ContextProvider
import com.niki914.zafiro.app.PermissionHolder
import com.niki914.zafiro.settings.RuntimeHostGateway
import kotlinx.coroutines.runBlocking

class IpcRuntimeHostGateway : RuntimeHostGateway {
    override suspend fun postNotification(
        title: String,
        content: String,
        uri: String?,
    ): Boolean {
        val context = ContextProvider.await()
        val pm = PermissionHolder.get(context)
        if (pm.status(Permission.NOTIFICATION) != PermissionState.GRANTED) {
            // ponytail: 挂起式等弹窗回调；阻塞式会卡宿主 Binder 线程，禁用
            var posted = false
            pm.scope().withPermission(Permission.NOTIFICATION) { result ->
                posted = result.finalState == PermissionState.GRANTED
            }
            if (!posted) return false
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
