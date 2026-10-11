package com.niki914.zafiro.api

import kotlinx.coroutines.flow.StateFlow

sealed interface McpServiceStatus {
    object Stopped : McpServiceStatus
    object Starting : McpServiceStatus
    data class Running(val host: String, val port: Int) : McpServiceStatus
    data class Error(val message: String, val stackTrace: String) : McpServiceStatus
}

interface McpService {
    val status: StateFlow<McpServiceStatus>
    suspend fun start()
    suspend fun stop()
    suspend fun restart()
}
