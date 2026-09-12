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
    val loopOptions: LoopOptions? = null,
    /**
     * 回合身份 token（不透明、值稳定）：定向 stop 按其值相等匹配（不要求同一对象，
     * 调用方可从原始身份重建）。不落盘、不写日志、不作权限凭证。
     * null = 未绑定身份：只能被无参 stop / 外部取消命中。
     */
    val turnToken: Any? = null
)
