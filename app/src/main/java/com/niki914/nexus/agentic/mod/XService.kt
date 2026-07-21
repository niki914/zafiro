package com.niki914.nexus.agentic.mod

import android.content.Context
import com.niki914.nexus.store.HostApp
import com.niki914.nexus.store.IpcReadResult
import com.niki914.nexus.store.IpcWriteResult
import com.niki914.nexus.store.XIpcBridge
import com.niki914.nexus.xposed.api.util.ContextProvider

object XService {

    suspend fun getLocalSettings(context: Context, client: XIpcBridge.StoreClient?): LocalSettings {
        return when (val result = XIpcBridge.readLocalSettingsJson(context, client)) {
            is IpcReadResult.Success -> LocalSettings(parseJsonObject(result.json))
            is IpcReadResult.Unreachable -> LocalSettings()
            is IpcReadResult.NotFound -> LocalSettings()
        }
    }

    suspend fun putLocalSettings(
        context: Context,
        settings: LocalSettings,
        client: XIpcBridge.StoreClient?
    ) {
        XIpcBridge.writeLocalSettingsJson(context, settings.props.toString(), client)
    }

    suspend fun postNotification(
        title: String,
        content: String,
        uri: String?,
        client: XIpcBridge.StoreClient?,
    ): Boolean {
        val context = ContextProvider.await()
        return XIpcBridge.postNotification(
            context,
            title,
            content,
            uri,
            client
        ) is IpcWriteResult.Success
    }

    fun postNetworkErrorNotification(client: XIpcBridge.StoreClient) {
        client.postNetworkErrorNotification()
    }

    fun postUnsupportedVersionNotification(
        hostApp: HostApp?,
        hostVersion: String?,
        client: XIpcBridge.StoreClient
    ) {
        client.postUnsupportedVersionNotification(hostApp?.packageName, hostVersion)
    }
}
