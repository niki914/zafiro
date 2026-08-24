package com.niki914.zafiro.chat

import java.util.concurrent.atomic.AtomicLong

data class ConversationTurnState(
    val turnId: Long = 0L,
    val lastQuery: String = "",
    val mode: TurnMode = TurnMode.InjectedLLM
) {
    fun nextTurn(query: String, mode: TurnMode) = ConversationTurnState(
        turnId = TurnIdGenerator.next(),
        lastQuery = query,
        mode = mode
    )
}

private object TurnIdGenerator {
    private val nextId = AtomicLong(System.currentTimeMillis())

    fun next(): Long = nextId.updateAndGet { previous ->
        maxOf(previous + 1L, System.currentTimeMillis())
    }
}
