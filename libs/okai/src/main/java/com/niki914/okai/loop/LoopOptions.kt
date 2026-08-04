package com.niki914.okai.loop

import com.niki914.okai.error.RetryPolicy

/**
 * Turn-level behavior options. Declared per send, distinct from connection config.
 *
 * Design source: kai PRD sections 4.4 and 4.7.
 */
data class LoopOptions(
    val segmentFailurePolicy: SegmentFailurePolicy = SegmentFailurePolicy.Discard,
    val turnRetryPolicy: RetryPolicy? = null
)

/**
 * What to do with partially produced content when a segment fails.
 * Applies inside one segment; the loop owns the boundary, hosts stay unaware.
 *
 * Design source: kai PRD section 4.4 segment atomicity.
 */
enum class SegmentFailurePolicy {
    Discard,
    Commit
}

/**
 * Stop semantics. Graceful waits for running tools, Force cancels them.
 * Graceful still needs an upper timeout bound so hosts never hang.
 *
 * Design source: kai PRD section 4.4 stop levels; Force closes the gap
 * where hosts had to kill processes before stopping (existing kai workaround).
 */
enum class StopMode {
    Graceful,
    Force
}
