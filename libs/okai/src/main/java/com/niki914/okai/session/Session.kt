package com.niki914.okai.session

import com.niki914.okai.message.Message

/**
 * One message with its position in the session tree. Id and parent id form
 * an append-only chain; a fork reuses the prefix chain under a new session id.
 *
 * Design source: pi (earendil-works/pi coding-agent session-manager.ts)
 * SessionEntryBase { id, parentId }, per kai PRD section 4.6.
 */
data class SessionEntry(
    val id: String,
    val parentId: String?,
    val timestamp: Long,
    val message: Message
)

/**
 * Turn history holder. Entries are the durable model; history is the linear
 * projection the loop consumes. Forking starts a new session from a past
 * entry, so hosts resume from an earlier point of a conversation.
 *
 * Design source: pi (earendil-works/pi coding-agent session-manager.ts)
 * session tree and codex thread-store prepare_fork boundary, limited to the
 * resume-from-past-node use case per kai PRD section 4.6.
 */
interface Session {

    val id: String

    val entries: List<SessionEntry>

    val history: List<Message>

    fun append(message: Message): SessionEntry

    fun forkFrom(entryId: String): Session

    fun clear()
}

/** How the session reacts to a new send while a turn is active. Declared in config. */
enum class ConcurrencyMode {
    Reject,
    Replace,
    Queue
}
