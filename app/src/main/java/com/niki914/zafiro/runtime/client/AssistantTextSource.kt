package com.niki914.zafiro.runtime.client

import com.niki914.zafiro.runtime.ipc.RenderFrame
import kotlinx.coroutines.flow.Flow

interface AssistantTextSource {
    fun submit(query: String): Flow<RenderFrame>
    suspend fun cancel()
    suspend fun resetConversation()
}
