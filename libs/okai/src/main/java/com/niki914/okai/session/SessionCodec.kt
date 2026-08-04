package com.niki914.okai.session

/**
 * Serialization contract for session entries. Storage location and backend
 * stay in the host; this only converts between entries and an exchange format.
 * Id, parent id and timestamp persist so hosts rebuild the tree after reload.
 *
 * Design source: kai PRD section 4.6 Session codec interface; entry model
 * from pi (earendil-works/pi coding-agent session-manager.ts).
 */
interface SessionCodec {

    fun encode(entries: List<SessionEntry>): String

    fun decode(raw: String): List<SessionEntry>
}
