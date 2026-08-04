package com.niki914.s3ss10n.util

import com.niki914.s3ss10n.ChatTurn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class HistoryKeeper {
    private val history: MutableList<ChatTurn> = mutableListOf()
    private val mutex = Mutex()

    suspend fun add(turn: ChatTurn) = mutex.withLock {
        history += turn
    }

    suspend fun snapshot(): List<ChatTurn> = mutex.withLock {
        history.toList()
    }

    suspend fun clear() = mutex.withLock {
        history.clear()
    }

    suspend fun replace(turns: List<ChatTurn>) = mutex.withLock {
        history.clear()
        history += turns
    }

    suspend fun dropLast(count: Int) = mutex.withLock {
        repeat(count.coerceAtMost(history.size)) {
            history.removeAt(history.lastIndex)
        }
    }

    suspend fun dropLastIfUserOrphan() = mutex.withLock {
        if (history.lastOrNull() is ChatTurn.User) {
            history.removeAt(history.lastIndex)
        }
    }
}
