package com.niki914.okai.protocol

import kotlin.reflect.KClass

/**
 * Resolves a protocol class to its singleton instance. Kept behind an
 * interface so tests register fake protocols; open() uses it to bind
 * a dialect to a session.
 *
 * Design source: existing kai (s3ss10n) ProtocolRegistry.
 */
interface ProtocolRegistry {

    fun register(protocolClass: KClass<out ChatProtocol>, factory: () -> ChatProtocol)

    fun resolve(protocolClass: KClass<out ChatProtocol>): ChatProtocol
}
