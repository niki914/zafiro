package com.niki914.libterm

data class SshOpenOptions(
    val host: String,
    val port: Int = DEFAULT_PORT,
    val username: String,
    val auth: SshAuth,
    val hostKeyPolicy: SshHostKeyPolicy = SshHostKeyPolicy.AcceptAny,
    val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    val serverAliveIntervalMillis: Int = DEFAULT_SERVER_ALIVE_INTERVAL_MILLIS,
) {
    init {
        require(host.trim().isNotEmpty()) { "SSH host is required" }
        require(port in 1..65535) { "SSH port must be in 1..65535" }
        require(username.trim().isNotEmpty()) { "SSH username is required" }
        require(connectTimeoutMillis > 0) { "SSH connect timeout must be greater than 0" }
        require(serverAliveIntervalMillis >= 0) { "SSH server alive interval must be greater than or equal to 0" }
    }

    companion object {
        const val DEFAULT_PORT: Int = 22
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS: Int = 10_000
        const val DEFAULT_SERVER_ALIVE_INTERVAL_MILLIS: Int = 30_000
    }
}
