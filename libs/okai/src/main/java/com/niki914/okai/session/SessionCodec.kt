package com.niki914.okai.session

/**
 * Serialization contract for one session snapshot. Storage location and
 * backend stay in the host; this only converts between a snapshot and an
 * exchange format. Id, parent id, entry ids and timestamps persist so hosts
 * rebuild the tree and fork chain after reload. The leaf is not persisted:
 * on reload the current position is the last entry, matching pi, because a
 * rewind is always followed by appends before a session is saved.
 *
 * Design source: kai PRD section 4.6 Session codec interface; entry model
 * from pi (earendil-works/pi coding-agent session-manager.ts).
 */
interface SessionCodec {

    fun encode(snapshot: SessionSnapshot): String

    fun decode(raw: String): SessionSnapshot
}

/** Persistable view of one session: identity, fork parent and entries. */
data class SessionSnapshot(
    val id: String,
    val parentSessionId: String?,
    val entries: List<SessionEntry>
)
