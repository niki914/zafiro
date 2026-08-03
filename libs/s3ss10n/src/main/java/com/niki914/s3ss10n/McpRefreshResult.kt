package com.niki914.s3ss10n

data class McpServerRefreshFailure(
    val serverName: String,
    val message: String
)

data class McpRefreshResult(
    val refreshedServers: List<String>,
    val failedServers: List<McpServerRefreshFailure>,
    val discoveredToolCount: Int
) {
    val isSuccess: Boolean
        get() = failedServers.isEmpty()

    val isPartialSuccess: Boolean
        get() = refreshedServers.isNotEmpty() && failedServers.isNotEmpty()
}
