package com.niki914.okai.codec

/**
 * JSON serialization abstraction. Keeps Okai free of any concrete JSON library.
 * Host supplies the real codec; tests supply a fake or a plain JVM codec.
 *
 * Design source: inherited from existing kai (s3ss10n) JsonCodec contract.
 */
interface JsonCodec {

    fun encode(value: Any?): String

    fun <T> decode(raw: String, type: Class<T>): T

    fun decodeObject(raw: String): Map<String, Any?>
}
