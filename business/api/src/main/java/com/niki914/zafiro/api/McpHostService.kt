package com.niki914.zafiro.api

import kotlinx.coroutines.flow.StateFlow

sealed interface McpHostStatus {
    object Stopped : McpHostStatus
    object Starting : McpHostStatus
    data class Running(val host: String, val port: Int) : McpHostStatus
    data class Error(val message: String) : McpHostStatus
}

interface McpHostService {
    val status: StateFlow<McpHostStatus>
    suspend fun start()
    suspend fun stop()
    suspend fun restart()
}
