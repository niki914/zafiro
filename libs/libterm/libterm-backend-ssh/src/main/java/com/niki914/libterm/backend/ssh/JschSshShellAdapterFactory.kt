package com.niki914.libterm.backend.ssh

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.niki914.libterm.SshAuth
import com.niki914.libterm.SshHostKeyPolicy
import com.niki914.libterm.SshOpenOptions
import com.niki914.libterm.TerminalBytes
import com.niki914.libterm.TerminalFailure
import com.niki914.libterm.TerminalIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.io.OutputStream as JavaOutputStream

internal class JschSshShellAdapterFactory(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SshShellAdapterFactory {
    override suspend fun open(options: SshOpenOptions): SshShellAdapter {
        return withContext(ioDispatcher) {
            val jsch = JSch()
            val session = jsch.getSession(
                options.username.trim(),
                options.host.trim(),
                options.port,
            )
            var channel: ChannelShell? = null
            try {
                configureHostKeyPolicy(jsch, session, options.hostKeyPolicy)
                configureAuth(session, options.auth)
                session.setServerAliveInterval(options.serverAliveIntervalMillis)
                session.connect(options.connectTimeoutMillis)

                channel = session.openChannel("shell") as ChannelShell
                channel.setPty(true)
                val shellOutput = channel.inputStream
                val shellInput = channel.outputStream
                channel.connect(options.connectTimeoutMillis)

                RealJschSshShellAdapter(
                    session = session,
                    channel = channel,
                    input = shellInput,
                    outputStream = shellOutput,
                    ioDispatcher = ioDispatcher,
                )
            } catch (error: Throwable) {
                runCatching { channel?.disconnect() }
                runCatching { session.disconnect() }
                throw error
            }
        }
    }

    private fun configureHostKeyPolicy(
        jsch: JSch,
        session: Session,
        hostKeyPolicy: SshHostKeyPolicy,
    ) {
        when (hostKeyPolicy) {
            SshHostKeyPolicy.AcceptAny -> {
                session.setConfig("StrictHostKeyChecking", "no")
            }

            is SshHostKeyPolicy.KnownHostsFile -> {
                val path = hostKeyPolicy.path.trim()
                if (path.isEmpty()) {
                    throw SshInvalidOpenOptionsException(
                        TerminalFailure.InvalidOpenOptions(
                            identity = TerminalIdentity.Ssh,
                            message = "SSH known hosts path is required",
                        ),
                    )
                }
                jsch.setKnownHosts(path)
                session.setConfig(
                    "StrictHostKeyChecking",
                    if (hostKeyPolicy.strict) "yes" else "ask",
                )
            }
        }
    }

    private fun configureAuth(
        session: Session,
        auth: SshAuth,
    ) {
        when (auth) {
            is SshAuth.Password -> {
                session.setPassword(auth.value)
                session.setConfig("PreferredAuthentications", "password")
            }

            is SshAuth.PrivateKey -> {
                throw SshInvalidOpenOptionsException(
                    TerminalFailure.InvalidOpenOptions(
                        identity = TerminalIdentity.Ssh,
                        message = "Private key authentication is not supported yet",
                    ),
                )
            }
        }
    }
}

private class RealJschSshShellAdapter(
    private val session: Session,
    private val channel: ChannelShell,
    private val input: JavaOutputStream,
    private val outputStream: InputStream,
    private val ioDispatcher: CoroutineDispatcher,
) : SshShellAdapter {
    private val closed = AtomicBoolean(false)
    private val exitSignal = CompletableDeferred<Throwable?>()

    override val output: Flow<TerminalBytes> = flow {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        try {
            while (!closed.get()) {
                val read = withContext(ioDispatcher) {
                    outputStream.read(buffer)
                }
                if (read < 0) {
                    break
                }
                if (read > 0) {
                    emit(TerminalBytes.of(buffer.copyOf(read)))
                }
            }
            completeExit(null)
        } catch (error: Throwable) {
            if (!closed.get()) {
                completeExit(error)
            }
            throw error
        }
    }

    override suspend fun write(input: TerminalBytes) {
        if (closed.get()) {
            throw IllegalStateException("SSH shell adapter is closed")
        }

        withContext(ioDispatcher) {
            this@RealJschSshShellAdapter.input.write(input.toByteArray())
            this@RealJschSshShellAdapter.input.flush()
        }
    }

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }

        withContext(ioDispatcher) {
            runCatching { input.close() }
            runCatching { outputStream.close() }
            runCatching { channel.disconnect() }
            runCatching { session.disconnect() }
        }
        completeExit(null)
    }

    override suspend fun awaitExit(): Throwable? {
        while (!closed.get() && !channel.isClosed) {
            delay(EXIT_POLL_INTERVAL_MILLIS)
        }
        if (channel.isClosed) {
            completeExit(null)
        }
        return exitSignal.await()
    }

    private fun completeExit(error: Throwable?) {
        if (exitSignal.complete(error)) {
            closed.set(true)
        }
    }

    private companion object {
        private const val EXIT_POLL_INTERVAL_MILLIS = 50L
    }
}
