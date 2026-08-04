package com.niki914.okai.runtime

import com.niki914.okai.loop.StopMode
import com.niki914.okai.loop.StopSignal

/**
 * Routes stop requests from Okai.stop into the active turn's signal.
 * At most one turn runs at a time per the session concurrency contract.
 *
 * Design source: independent design; replaces the host-side process-kill
 * workaround per kai PRD section 4.4 stop levels.
 */
interface StopController {

    fun createSignal(): StopSignal

    fun requestStop(mode: StopMode)

    fun cancel()
}
