package com.niki914.libterm.runtime

import com.niki914.libterm.SshAuth
import com.niki914.libterm.SshHostKeyPolicy
import com.niki914.libterm.SshOpenOptions

class SshOpenSpec {
    lateinit var host: String
    var port: Int = SshOpenOptions.DEFAULT_PORT
    lateinit var username: String
    var hostKeyPolicy: SshHostKeyPolicy = SshHostKeyPolicy.AcceptAny
    var connectTimeoutMillis: Int = SshOpenOptions.DEFAULT_CONNECT_TIMEOUT_MILLIS
    var serverAliveIntervalMillis: Int = SshOpenOptions.DEFAULT_SERVER_ALIVE_INTERVAL_MILLIS

    private var auth: SshAuth? = null

    fun password(value: String) {
        auth = SshAuth.Password(value)
    }

    internal fun toOpenOptions(): SshOpenOptions {
        if (!::host.isInitialized) {
            throw SshOpenOptionsException("SSH host is required")
        }
        if (!::username.isInitialized) {
            throw SshOpenOptionsException("SSH username is required")
        }

        return try {
            SshOpenOptions(
                host = host,
                port = port,
                username = username,
                auth = auth ?: throw SshOpenOptionsException("SSH password is required"),
                hostKeyPolicy = hostKeyPolicy,
                connectTimeoutMillis = connectTimeoutMillis,
                serverAliveIntervalMillis = serverAliveIntervalMillis,
            )
        } catch (error: SshOpenOptionsException) {
            throw error
        } catch (error: IllegalArgumentException) {
            throw SshOpenOptionsException(error.message ?: "Invalid SSH open options")
        }
    }
}

internal class SshOpenOptionsException(
    message: String,
) : IllegalArgumentException(message)
