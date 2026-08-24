package com.niki914.zafiro.chat

import java.util.concurrent.atomic.AtomicReference

object ActiveTurnStore {
    private val current = AtomicReference<ConversationTurnState?>(null)

    fun getCurrent(): ConversationTurnState? = current.get()

    fun setCurrent(state: ConversationTurnState) {
        current.set(state)
    }

    fun clear() {
        current.set(null)
    }

    fun isCurrentInjected(): Boolean = getCurrent()?.mode == TurnMode.InjectedLLM

    fun isActiveInjection(turnId: Long): Boolean {
        val state = getCurrent() ?: return false
        return state.turnId == turnId && state.mode == TurnMode.InjectedLLM
    }

    fun hasActiveTurn(): Boolean = getCurrent() != null
}
