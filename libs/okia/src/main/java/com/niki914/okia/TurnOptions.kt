package com.niki914.okia

import com.niki914.okia.loop.LoopOptions

/**
 * 每次 send 的回合级参数，覆盖 config 一次。
 * Design source: okia 骨架 TurnOptions。
 */
data class TurnOptions(
    val systemPrompt: String? = null,
    val model: String? = null,
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val loopOptions: LoopOptions? = null
)
