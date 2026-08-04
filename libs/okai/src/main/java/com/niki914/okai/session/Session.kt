package com.niki914.okai.session

import com.niki914.okai.message.Message

/**
 * Turn history holder. The loop appends messages; hosts read, replace and
 * fork. Forking stays a host concern (snapshot switching), so the tree-shaped
 * history of coding agents never enters this library.
 *
 * Design source: kai PRD section 4.6; host-side fork decision from the
 * kai PRD TODO list, resolved as "fork stays in host, Kai offers primitives".
 */
interface Session {

    val history: List<Message>

    val isBusy: Boolean

    fun append(message: Message)

    fun replace(history: List<Message>)

    fun clear()

    fun forkFrom(history: List<Message>): Session
}

/** How the session reacts to a new send while a turn is active. Declared in config. */
enum class ConcurrencyMode {
    Reject,
    Replace,
    Queue
}
