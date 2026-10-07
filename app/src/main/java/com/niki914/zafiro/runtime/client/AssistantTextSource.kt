package com.niki914.zafiro.runtime.client

import kotlinx.coroutines.flow.Flow

interface AssistantTextSource {
    fun submit(query: String): Flow<AssistantFrame>
    suspend fun cancel()
    suspend fun resetConversation()
}

