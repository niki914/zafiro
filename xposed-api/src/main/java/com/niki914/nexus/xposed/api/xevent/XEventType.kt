package com.niki914.nexus.xposed.api.xevent

import kotlinx.serialization.Serializable

@Serializable
enum class XEventType {
    INPUT_CAPTURED,
    TURN_DECIDED,
    LLM_ROUND_STARTED,
    LLM_TEXT_DELTA,
    LLM_ROUND_COMPLETED,
    LLM_ERROR,
    HOOK_FAILED,
    NATIVE_RESPONSE_BLOCKED,
    RENDER_TARGET_MISSING,
    RENDER_FIRST_CHUNK_INJECTED,
    RENDER_FINALIZED
}
