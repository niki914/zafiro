package com.niki914.okai.runtime

/**
 * Time source for retry delays, idle detection and session timestamps.
 * Injectable so tests control time without sleeping.
 *
 * Design source: pi (earendil-works/pi) clock injection; per kai PRD section 4.7.
 */
interface Clock {

    fun nowMillis(): Long
}
