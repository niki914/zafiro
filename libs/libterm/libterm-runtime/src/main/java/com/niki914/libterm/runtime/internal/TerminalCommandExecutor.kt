package com.niki914.libterm.runtime.internal

import com.niki914.libterm.OutputStream
import com.niki914.libterm.SendResult
import com.niki914.libterm.SessionState
import com.niki914.libterm.TerminalBytes
import com.niki914.libterm.TerminalSession
import com.niki914.libterm.runtime.CommandTerminationReason
import com.niki914.libterm.runtime.CommandResult
import com.niki914.libterm.runtime.Term
import com.niki914.libterm.runtime.TermResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream

internal object TerminalCommandExecutor {
    suspend fun exec(
        session: TerminalSession,
        command: String,
        timeoutMillis: Long = Term.DEFAULT_EXEC_TIMEOUT_MILLIS,
    ): TermResult<CommandResult> {
        val execId = RuntimeIdGenerator().nextId()
        val prefixText = "\n$EXEC_MARKER_PREFIX$execId:"
        val suffixText = "$EXEC_MARKER_SUFFIX\n"
        val outputLock = Any()
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val completed = CompletableDeferred<CommandResult>()

        return coroutineScope {
            val collectorJob = launch(start = CoroutineStart.UNDISPATCHED) {
                // exec marker 解析必须继续消费 raw bytes，不能接入文本 decoder。
                session.output.collect { chunk ->
                    when (chunk.stream) {
                        OutputStream.STDOUT -> {
                            val result = synchronized(outputLock) {
                                stdout.write(chunk.bytes.toByteArray())
                                parseExecResult(
                                    command = command,
                                    stdoutBytes = stdout.toByteArray(),
                                    stderrBytes = stderr.toByteArray(),
                                    prefixText = prefixText,
                                    suffixText = suffixText,
                                )
                            }
                            result?.let {
                                if (!completed.isCompleted) {
                                    completed.complete(it)
                                }
                            }
                        }

                        OutputStream.STDERR -> synchronized(outputLock) {
                            stderr.write(chunk.bytes.toByteArray())
                        }
                    }
                }
            }
            val sessionEnded = async(start = CoroutineStart.UNDISPATCHED) {
                session.state.first { state ->
                    when (state) {
                        SessionState.Running -> false
                        SessionState.Starting -> false
                        SessionState.Closed -> true
                        is SessionState.Failed -> true
                    }
                }
            }

            when (val sendResult = session.send(buildExecPayload(command, execId).encodeToByteArray())) {
                SendResult.Sent -> Unit
                is SendResult.Failed -> {
                    sessionEnded.cancel()
                    collectorJob.cancel()
                    return@coroutineScope TermResult.Failure(sendResult.failure)
                }
            }

            val completion = if (timeoutMillis > 0) {
                withTimeoutOrNull(timeoutMillis) {
                    awaitCompletion(
                        completed = completed,
                        sessionEnded = sessionEnded,
                    )
                } ?: ExecCompletion.TimedOut
            } else {
                awaitCompletion(
                    completed = completed,
                    sessionEnded = sessionEnded,
                )
            }

            sessionEnded.cancel()
            collectorJob.cancel()
            TermResult.Success(
                when (completion) {
                    is ExecCompletion.Completed -> completion.result
                    ExecCompletion.SessionTerminated -> snapshotResult(
                        command = command,
                        stdout = stdout,
                        stderr = stderr,
                        outputLock = outputLock,
                        terminationReason = CommandTerminationReason.SESSION_TERMINATED,
                    )
                    ExecCompletion.TimedOut -> snapshotResult(
                        command = command,
                        stdout = stdout,
                        stderr = stderr,
                        outputLock = outputLock,
                        terminationReason = CommandTerminationReason.TIMED_OUT,
                    )
                },
            )
        }
    }

    private fun buildExecPayload(command: String, execId: String): String {
        val normalizedCommand = if (command.endsWith('\n')) command else "$command\n"
        return buildString {
            append(normalizedCommand)
            append("__libterm_exec_status=$?; printf '\\n")
            append(EXEC_MARKER_PREFIX)
            append(execId)
            append(":%s")
            append(EXEC_MARKER_SUFFIX)
            append("\\n' \"\$__libterm_exec_status\"")
            append('\n')
        }
    }

    private fun parseExecResult(
        command: String,
        stdoutBytes: ByteArray,
        stderrBytes: ByteArray,
        prefixText: String,
        suffixText: String,
    ): CommandResult? {
        val prefixBytes = prefixText.encodeToByteArray()
        val suffixBytes = suffixText.encodeToByteArray()
        val markerStart = stdoutBytes.indexOf(prefixBytes)
        if (markerStart < 0) {
            return null
        }
        val markerEnd = stdoutBytes.indexOf(suffixBytes, markerStart + prefixBytes.size)
        if (markerEnd < 0) {
            return null
        }
        val exitCodeBytes = stdoutBytes.copyOfRange(markerStart + prefixBytes.size, markerEnd)
        val exitCode = exitCodeBytes.parseAsciiInt() ?: return null
        return CommandResult(
            command = command,
            stdout = TerminalBytes.of(stdoutBytes.copyOfRange(0, markerStart)),
            stderr = TerminalBytes.of(stderrBytes),
            exitCode = exitCode,
            timedOut = false,
            terminationReason = CommandTerminationReason.COMPLETED,
        )
    }

    private suspend fun awaitCompletion(
        completed: CompletableDeferred<CommandResult>,
        sessionEnded: kotlinx.coroutines.Deferred<*>,
    ): ExecCompletion {
        return select {
            completed.onAwait { ExecCompletion.Completed(it) }
            sessionEnded.onAwait { ExecCompletion.SessionTerminated }
        }
    }

    private fun snapshotResult(
        command: String,
        stdout: ByteArrayOutputStream,
        stderr: ByteArrayOutputStream,
        outputLock: Any,
        terminationReason: CommandTerminationReason,
    ): CommandResult {
        return synchronized(outputLock) {
            CommandResult(
                command = command,
                stdout = TerminalBytes.of(stdout.toByteArray()),
                stderr = TerminalBytes.of(stderr.toByteArray()),
                exitCode = null,
                timedOut = terminationReason == CommandTerminationReason.TIMED_OUT,
                terminationReason = terminationReason,
            )
        }
    }

    private fun ByteArray.indexOf(target: ByteArray, startIndex: Int = 0): Int {
        if (target.isEmpty()) {
            return startIndex.coerceAtMost(size)
        }
        val lastStart = size - target.size
        for (index in startIndex..lastStart) {
            var matched = true
            for (targetIndex in target.indices) {
                if (this[index + targetIndex] != target[targetIndex]) {
                    matched = false
                    break
                }
            }
            if (matched) {
                return index
            }
        }
        return -1
    }

    private fun ByteArray.parseAsciiInt(): Int? {
        if (isEmpty()) {
            return null
        }
        var value = 0
        for (byte in this) {
            if (byte < '0'.code.toByte() || byte > '9'.code.toByte()) {
                return null
            }
            value = (value * 10) + (byte - '0'.code.toByte())
        }
        return value
    }

    private const val EXEC_MARKER_PREFIX = "__LIBTERM_EXIT_"
    private const val EXEC_MARKER_SUFFIX = "__"

    private sealed interface ExecCompletion {
        data class Completed(val result: CommandResult) : ExecCompletion

        data object SessionTerminated : ExecCompletion

        data object TimedOut : ExecCompletion
    }
}
