package com.niki914.okai.session

/**
 * Serialization contract for one session snapshot. Storage location and
 * backend stay in the host; this only converts between a snapshot and an
 * exchange format. Id, parent id, entry ids, the leaf position and
 * timestamps persist so hosts rebuild the tree, the fork chain and the
 * current position after reload. leafId null means the position falls back
 * to the last entry. version lets hosts migrate snapshots when the schema
 * evolves.
 *
 * Design source: kai PRD section 4.6 Session codec interface; leaf handling
 * from pi (earendil-works/pi coding-agent session-manager.ts), whose
 * buildSessionPath accepts an explicit leafId with the last entry as
 * fallback, and whose session files carry a header version.
 */
interface SessionCodec {

    fun encode(snapshot: SessionSnapshot): String

    fun decode(raw: String): SessionSnapshot
}

/**
 * Persistable view of one session: identity, fork parent, current leaf and
 * entries. The leaf is the current position; without it a rewind would be
 * lost on reload.
 */
data class SessionSnapshot(
    val id: String,
    val parentSessionId: String?,
    val leafId: String?,
    val version: Int,
    val entries: List<SessionEntry>
)
