package com.niki914.okai.codec

import java.lang.reflect.Type

/**
 * JSON serialization abstraction. Keeps Okai free of any concrete JSON library.
 * Host supplies the real codec; tests supply a fake or a plain JVM codec.
 * decode takes a Type so generic targets (lists, sealed hierarchies) survive
 * without per-type wrappers.
 *
 * Design source: independent design; codec-free library requirement per kai PRD section 2.
 */
interface JsonCodec {

    fun encode(value: Any?): String

    fun <T> decode(raw: String, type: Type): T

    fun decodeObject(raw: String): Map<String, Any?>
}
