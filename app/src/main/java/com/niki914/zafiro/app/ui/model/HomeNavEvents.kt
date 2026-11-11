package com.niki914.zafiro.app.ui.model

import kotlinx.coroutines.flow.MutableStateFlow

/** One-shot requests from other pages to the Home page (a flag, not an event stream, so it survives Home being off screen). */
object HomeNavEvents {
    val newChatRequested = MutableStateFlow(false)
}
