package com.niki914.okai

import com.niki914.okai.loop.LoopOptions

/**
 * Per-turn parameters passed to Okai.send, overriding config values for one
 * turn only. systemPrompt lives here because hosts rebuild prompts every turn
 * (kai PRD keeps prompt building in the host layer, section 2).
 *
 * Design source: independent design; request-level parameters align with
 * pi SimpleStreamOptions and codex per-turn input, per kai PRD section 4.4.
 */
data class TurnOptions(
    val systemPrompt: String? = null,
    val model: String? = null,
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val loopOptions: LoopOptions? = null
)
