package com.niki914.okai.protocol

import kotlin.reflect.KClass

/**
 * Resolves a protocol by stable id or class. Ids are stable identifiers
 * that hosts persist alongside their session data (e.g. "deepseek") and
 * resolve after reload; classes bind dialects at open(). Registering the
 * same id again replaces the previous factory.
 *
 * Design source: independent design; id-based lookup required by the kai PRD
 * TODO on protocol switching and session restore.
 */
interface ProtocolRegistry {

    fun register(factory: () -> ChatProtocol)

    fun resolve(id: String): ChatProtocol?

    fun resolve(protocolClass: KClass<out ChatProtocol>): ChatProtocol?
}
