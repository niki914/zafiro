package com.niki914.s3ss10n.net

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile

data class SseEvent(
    val event: String?,
    val data: String
)

object SseLineParser {
    fun parse(rawLines: Flow<String>): Flow<String> = parseEvents(rawLines)
        .map { it.data }
        .takeWhile { it != "[DONE]" }

    fun parseEvents(rawLines: Flow<String>): Flow<SseEvent> = flow {
        var eventName: String? = null
        val dataLines = mutableListOf<String>()

        suspend fun flushEvent() {
            if (dataLines.isEmpty()) return
            emit(SseEvent(eventName, dataLines.joinToString("\n")))
            eventName = null
            dataLines.clear()
        }

        rawLines.collect { line ->
            when {
                line.isEmpty() -> flushEvent()
                line.startsWith(":") -> Unit
                else -> {
                    val separatorIndex = line.indexOf(':')
                    val field = if (separatorIndex >= 0) line.substring(0, separatorIndex) else line
                    val rawValue = if (separatorIndex >= 0) line.substring(separatorIndex + 1) else ""
                    val value = rawValue.removePrefix(" ")

                    when (field) {
                        "event" -> eventName = value
                        "data" -> dataLines += value
                    }
                }
            }
        }

        flushEvent()
    }
}
