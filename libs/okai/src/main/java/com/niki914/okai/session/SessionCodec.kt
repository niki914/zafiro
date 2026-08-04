package com.niki914.okai.session

import com.niki914.okai.message.Message

/**
 * Serialization contract for session history. Storage location and backend
 * stay in the host; this only converts between messages and an exchange format.
 *
 * Design source: kai PRD section 4.6 Session codec interface.
 */
interface SessionCodec {

    fun encode(history: List<Message>): String

    fun decode(raw: String): List<Message>
}
