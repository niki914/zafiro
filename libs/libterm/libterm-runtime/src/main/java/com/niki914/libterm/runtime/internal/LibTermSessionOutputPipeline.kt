package com.niki914.libterm.runtime.internal

import com.niki914.libterm.TerminalSession
import com.niki914.libterm.runtime.SessionTerminalOutputDecoder
import com.niki914.libterm.runtime.TerminalTextChunk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

internal class LibTermSessionOutputPipeline(
    private val session: TerminalSession,
    private val decoder: SessionTerminalOutputDecoder,
) {
    private val bufferLock = Any()
    private val buffer = mutableListOf<TerminalTextChunk>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val outputEvents = MutableSharedFlow<TerminalTextChunk>(
        replay = 0,
        extraBufferCapacity = 64,
    )
    private val collectionJob: Job

    init {
        bootstrapBufferedOutput()
        collectionJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            session.output.collect { chunk ->
                decoder.decode(chunk).forEach { textChunk ->
                    appendChunk(textChunk)
                    outputEvents.emit(textChunk)
                }
            }
        }
    }

    val stream: Flow<TerminalTextChunk> = outputEvents.asSharedFlow()

    fun latest(limit: Int): List<TerminalTextChunk> {
        if (limit <= 0) {
            return emptyList()
        }

        return synchronized(bufferLock) {
            buffer.takeLast(limit).toList()
        }
    }

    fun reset() {
        synchronized(bufferLock) {
            buffer.clear()
        }
        decoder.reset()
    }

    fun close() {
        collectionJob.cancel()
        scope.cancel()
        reset()
    }

    private fun appendChunk(chunk: TerminalTextChunk) {
        synchronized(bufferLock) {
            buffer += chunk
            trimBufferLocked()
        }
    }

    private fun bootstrapBufferedOutput() {
        session.latest(Int.MAX_VALUE).forEach { chunk ->
            decoder.decode(chunk).forEach(::appendChunk)
        }
    }

    private fun trimBufferLocked() {
        var bufferedCharCount = buffer.sumOf { it.text.length }
        while (buffer.size > MAX_BUFFERED_CHUNK_COUNT ||
            (bufferedCharCount > MAX_BUFFERED_CHAR_COUNT && buffer.size > 1)
        ) {
            val removed = buffer.removeAt(0)
            bufferedCharCount -= removed.text.length
        }
    }

    private companion object {
        const val MAX_BUFFERED_CHUNK_COUNT: Int = 256
        const val MAX_BUFFERED_CHAR_COUNT: Int = 65_536
    }
}
