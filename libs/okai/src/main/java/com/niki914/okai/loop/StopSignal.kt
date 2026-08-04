package com.niki914.okai.loop

/**
 * Per-turn stop handle polled by the loop between segments. Null means no
 * stop was requested. Graceful stops new segments but lets running tools
 * finish; Force cancels everything.
 *
 * Design source: pi (earendil-works/pi packages/ai streams/abort-signals.ts)
 * and codex cancellation, simplified to stop semantics per kai PRD section 4.4.
 */
interface StopSignal {

    fun requestedStopMode(): StopMode?
}
