package com.niki914.okai.transport

/**
 * One raw SSE line before protocol parsing. A null data marks a comment or
 * keep-alive line; the parser keeps it as idle evidence rather than dropping it.
 *
 * Design source: independent design; aligns the kai PRD section 4.3 parseStream
 * input with the transport-activity idle rule (kai PRD section 4.4).
 */
data class SseLine(val data: String?)
