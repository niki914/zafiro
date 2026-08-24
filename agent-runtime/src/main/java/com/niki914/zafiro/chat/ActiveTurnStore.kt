package com.niki914.zafiro.chat

import java.util.concurrent.atomic.AtomicReference

object ActiveTurnStore {
    private val current = AtomicReference<com.niki914.zafiro.chat.ConversationTurnState?>(null)

    fun getCurrent(): com.niki914.zafiro.chat.ConversationTurnState? = current.get()

    fun setCurrent(state: com.niki914.zafiro.chat.ConversationTurnState) {
        current.set(state)
    }

    fun clear() {
        current.set(null)
    }

    fun isCurrentInjected(): Boolean = getCurrent()?.mode == _root_ide_package_.com.niki914.zafiro.chat.TurnMode.InjectedLLM

    fun isActiveInjection(turnId: Long): Boolean {
        val state = getCurrent() ?: return false
        return state.turnId == turnId && state.mode == _root_ide_package_.com.niki914.zafiro.chat.TurnMode.InjectedLLM
    }

    fun hasActiveTurn(): Boolean = getCurrent() != null
}
