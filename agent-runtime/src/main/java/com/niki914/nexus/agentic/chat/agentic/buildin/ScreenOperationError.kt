package com.niki914.nexus.agentic.chat.agentic.buildin

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class ScreenOperationError(val code: String) {
    INVALID_ARGUMENTS_JSON("INVALID_ARGUMENTS_JSON"),
    INVALID_OPERATION("INVALID_OPERATION"),
    INVALID_ARGUMENTS("INVALID_ARGUMENTS"),
    SERVICE_UNAVAILABLE("SERVICE_UNAVAILABLE"),
    VERSION_MISMATCH("VERSION_MISMATCH"),
    NODE_NOT_FOUND("NODE_NOT_FOUND"),
    NODE_STALE("NODE_STALE"),
    UI_CHANGED("UI_CHANGED"),
    NODE_HIDDEN("NODE_HIDDEN"),
    SET_TEXT_FAILED("SET_TEXT_FAILED"),
    SHELL_NOT_AVAILABLE("SHELL_NOT_AVAILABLE"),
    SHELL_FAILED("SHELL_FAILED"),
    SHELL_TIMEOUT("SHELL_TIMEOUT"),
    SHELL_SESSION_LOST("SHELL_SESSION_LOST"),
    INTERNAL_ERROR("INTERNAL_ERROR"),
    SEARCH_FAILED("SEARCH_FAILED"),
    KEY_EVENT_FAILED("KEY_EVENT_FAILED"),
    ;

    fun toJsonString(message: String): String {
        return JsonObject(
            mapOf(
                "error" to JsonObject(
                    mapOf(
                        "code" to JsonPrimitive(code),
                        "message" to JsonPrimitive(message),
                    )
                ),
            )
        ).toString()
    }

    companion object {
        fun errorJson(code: String, message: String): String {
            return JsonObject(
                mapOf(
                    "error" to JsonObject(
                        mapOf(
                            "code" to JsonPrimitive(code),
                            "message" to JsonPrimitive(message),
                        )
                    ),
                )
            ).toString()
        }
    }
}
