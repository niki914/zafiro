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
 * Turn history holder. Entries form a tree via parent ids; leafId is the
 * current position and advances on every append. history is the projection
 * from leaf back to root that the loop consumes, so rewound sessions expose
 * only the active path while abandoned tails stay in the tree for later
 * rewinds or forks. Forking returns a new session whose entries share the
 * immutable node objects with the source; independence comes from
 * immutability, not from cloning.
 *
 * Design source: pi (earendil-works/pi coding-agent session-manager.ts)
 * SessionEntryBase tree, leafId pointer and createBranchedSession, per kai
 * PRD section 4.6.
 */
interface Session {

    val id: String

    /** Session this one was forked from, or null for a root session. */
    val parentSessionId: String?

    val entries: List<SessionEntry>

    /** Messages on the path from leaf to root, in conversation order. */
    val history: List<Message>

    /** Current position in the tree; advances on append, moves on rewind. */
    val leafId: String?

    fun append(message: Message): SessionEntry

    /**
     * New independent session carrying the path root-to-entryId. The new
     * leaf is entryId; further appends on either session never affect the
     * other.
     */
    fun forkFrom(entryId: String): Session

    /** Moves the current position to a past entry. The skipped tail stays in the tree. */
    fun rewind(entryId: String)

    fun clear()
}

/** How the session reacts to a new send while a turn is active. Declared in config. */
enum class ConcurrencyMode {
    Reject,
    Replace,
    Queue
}
