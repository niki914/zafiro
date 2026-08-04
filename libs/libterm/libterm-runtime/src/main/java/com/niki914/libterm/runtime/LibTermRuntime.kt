package com.niki914.libterm.runtime

import com.niki914.libterm.OpenResult
import com.niki914.libterm.TerminalFailure
import com.niki914.libterm.TerminalIdentity
import com.niki914.libterm.TerminalManager
import com.niki914.libterm.TerminalOpenOptions
import com.niki914.libterm.TerminalSession
import com.niki914.libterm.runtime.internal.LambdaSessionOutputDecoder

class LibTermRuntime internal constructor(
    private val manager: TerminalManager,
    private val config: LibTermRuntimeConfig = LibTermRuntimeConfig(),
) {
    private val sessionLock = Any()
    private val sessionsById = LinkedHashMap<String, LibTermSession>()

    suspend fun open(configure: TerminalOpenSpec.() -> Unit): OpenResult<LibTermSession> {
        val spec = try {
            TerminalOpenSpec().apply(configure)
        } catch (error: SshOpenOptionsException) {
            return OpenResult.Failure(
                TerminalFailure.InvalidOpenOptions(
                    identity = TerminalIdentity.Ssh,
                    message = error.message,
                ),
            )
        }
        if (!spec.hasIdentity()) {
            return OpenResult.Failure(
                TerminalFailure.InvalidOpenOptions(
                    identity = null,
                    message = "identity is required",
                ),
            )
        }

        return when (val result = manager.open(
            identity = spec.identity,
            authorizationMode = spec.authorizationMode ?: config.defaultAuthorizationMode,
            openOptions = TerminalOpenOptions(
                cwd = spec.cwd ?: config.defaultCwd,
                ssh = spec.sshOptions,
            ),
        )) {
            is OpenResult.Success -> OpenResult.Success(wrapSession(result.value))
            is OpenResult.Failure -> result
        }
    }

    fun getSession(id: String): LibTermSession? {
        return manager.get(id)?.let(::wrapSession)
    }

    fun listSessions(): List<LibTermSession> {
        return manager.list().map(::wrapSession)
    }

    suspend fun close(sessionId: String): Boolean {
        val closed = manager.close(sessionId)
        if (closed) {
            val removed = synchronized(sessionLock) {
                sessionsById.remove(sessionId)
            }
            removed?.dispose()
        }
        return closed
    }

    suspend fun closeAll(): Int {
        var closedCount = 0
        val closedSessions = mutableListOf<LibTermSession>()
        for (session in manager.list()) {
            if (manager.close(session.id)) {
                closedCount += 1
                val removed = synchronized(sessionLock) {
                    sessionsById.remove(session.id)
                }
                if (removed != null) {
                    closedSessions += removed
                }
            }
        }
        closedSessions.forEach(LibTermSession::dispose)
        return closedCount
    }

    private fun wrapSession(session: TerminalSession): LibTermSession {
        return synchronized(sessionLock) {
            sessionsById.getOrPut(session.id) {
                LibTermSession(
                    session = session,
                    closeSession = ::close,
                    decoder = createSessionDecoder(),
                )
            }
        }
    }

    private fun createSessionDecoder(): SessionTerminalOutputDecoder {
        val outputDecode = config.outputDecode
        return if (outputDecode != null) {
            LambdaSessionOutputDecoder(outputDecode)
        } else {
            config.outputDecoder.createSessionDecoder()
        }
    }
}
