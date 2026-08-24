package com.niki914.zafiro.chat

sealed interface TurnMode {
    data object InjectedLLM : TurnMode
    data object NativeTakeover : TurnMode
}
