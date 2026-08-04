package com.niki914.libterm.backend.shizuku.internal

import com.niki914.libterm.backend.shizuku.ILibTermShizukuShellCallback
import com.niki914.libterm.backend.shizuku.ILibTermShizukuShellService
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal class LibTermShizukuShellUserService : ILibTermShizukuShellService.Stub() {
    private val nextSessionId = AtomicLong(0L)
    private val sessions = ConcurrentHashMap<Long, ShellSession>()

    override fun openSession(cwd: String, callback: ILibTermShizukuShellCallback): Long {
        val sessionId = nextSessionId.incrementAndGet()
        val process = try {
            val processBuilder = ProcessBuilder("sh")
                .redirectErrorStream(false)
            if (cwd.isNotEmpty()) {
                processBuilder.directory(File(cwd))
            }
            processBuilder.start()
        } catch (error: Throwable) {
            callback.safeOnError(sessionId, "Failed to start Shizuku shell: ${error.message}")
            throw IllegalStateException("Failed to start Shizuku shell", error)
        }

        val session = ShellSession(
            id = sessionId,
            process = process,
            callback = callback,
            stdin = process.outputStream,
        )
        sessions[sessionId] = session
        session.start()
        return sessionId
    }

    override fun write(sessionId: Long, data: ByteArray) {
        val session = sessions[sessionId]
            ?: throw IllegalStateException("Shizuku shell session not found")
        if (session.closed.get()) {
            throw IllegalStateException("Shizuku shell session is closed")
        }

        try {
            synchronized(session.stdinLock) {
                session.stdin.write(data)
                session.stdin.flush()
            }
        } catch (error: Throwable) {
            session.callback.safeOnError(
                sessionId,
                "Failed to write Shizuku shell stdin: ${error.message}"
            )
            closeSession(session)
            throw IllegalStateException("Failed to write Shizuku shell stdin", error)
        }
    }

    override fun close(sessionId: Long) {
        val session = sessions[sessionId] ?: return
        closeSession(session)
    }

    private fun ShellSession.start() {
        stdoutThread = newPumpThread(
            name = "libterm-shizuku-stdout-$id",
            input = process.inputStream,
            stream = ShizukuShellConstants.STREAM_STDOUT,
        )
        stderrThread = newPumpThread(
            name = "libterm-shizuku-stderr-$id",
            input = process.errorStream,
            stream = ShizukuShellConstants.STREAM_STDERR,
        )
        waitThread = Thread({
            val exitCode = try {
                process.waitFor()
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                return@Thread
            }

            if (closed.compareAndSet(false, true)) {
                sessions.remove(id)
                closeStreams()
                callback.safeOnClosed(id, exitCode)
            }
        }, "libterm-shizuku-wait-$id").also { it.start() }
    }

    private fun ShellSession.newPumpThread(
        name: String,
        input: InputStream,
        stream: Int,
    ): Thread {
        return Thread({
            val buffer = ByteArray(ShizukuShellConstants.OUTPUT_CHUNK_SIZE_BYTES)
            try {
                while (!closed.get()) {
                    val read = input.read(buffer)
                    if (read < 0) {
                        break
                    }
                    if (read > 0) {
                        callback.safeOnOutput(id, stream, buffer.copyOf(read))
                    }
                }
            } catch (error: Throwable) {
                if (!closed.get()) {
                    callback.safeOnError(
                        id,
                        "Failed to read Shizuku shell output: ${error.message}"
                    )
                    closeSession(this)
                }
            }
        }, name).also { it.start() }
    }

    private fun closeSession(session: ShellSession) {
        if (!session.closed.compareAndSet(false, true)) {
            return
        }

        sessions.remove(session.id)
        session.closeStreams()
        runCatching { session.process.destroy() }
        runCatching {
            if (session.process.isAlive) {
                session.process.destroyForcibly()
            }
        }
        session.stdoutThread?.interrupt()
        session.stderrThread?.interrupt()
        session.waitThread?.interrupt()
    }

    private fun ShellSession.closeStreams() {
        runCatching { stdin.close() }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
    }

    private fun ILibTermShizukuShellCallback.safeOnOutput(
        sessionId: Long,
        stream: Int,
        data: ByteArray,
    ) {
        runCatching { onOutput(sessionId, stream, data) }
    }

    private fun ILibTermShizukuShellCallback.safeOnClosed(sessionId: Long, exitCode: Int) {
        runCatching { onClosed(sessionId, exitCode) }
    }

    private fun ILibTermShizukuShellCallback.safeOnError(sessionId: Long, message: String) {
        runCatching { onError(sessionId, message) }
    }

    private class ShellSession(
        val id: Long,
        val process: Process,
        val callback: ILibTermShizukuShellCallback,
        val stdin: OutputStream,
    ) {
        val closed = AtomicBoolean(false)
        val stdinLock = Any()

        @Volatile
        var stdoutThread: Thread? = null

        @Volatile
        var stderrThread: Thread? = null

        @Volatile
        var waitThread: Thread? = null
    }
}
