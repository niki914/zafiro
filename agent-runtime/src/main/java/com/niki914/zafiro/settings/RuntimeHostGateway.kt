package com.niki914.zafiro.settings

interface RuntimeHostGateway {
    suspend fun postNotification(title: String, content: String, uri: String?): Boolean
}
