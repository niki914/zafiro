package com.niki914.nexus.agentic.chat.agentic.buildin.impl

import com.niki914.nexus.agentic.chat.agentic.accessibility.AccessibilityController
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.nexus.agentic.chat.agentic.buildin.RawBuiltinTool
import com.niki914.s3ss10n.LocalToolConfig
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class ScreenContentBuiltin : RawBuiltinTool() {
    override val name: String = "screen_content"

    override val description: String =
        "Read the current screen accessibility tree as YAML. " +
                "If the tree is empty or contains only a root node, the current app likely uses " +
                "a non-native UI framework (Flutter, Unity, WebView, game) that does not expose " +
                "standard Android accessibility nodes — screen_content/node_action/gesture cannot " +
                "interact with such apps, and you should stop retrying immediately." +
                "\n\nKey glossary: " +
                "i=index, t=semantic_type(button/input/text/image/list/list_item/switch/checkbox/tab/chip/toolbar/dialog/container), " +
                "b=bounds[left,top,right,bottom], txt=display_text, h=content_description/identifier, " +
                "tap=clickable, hold=long_clickable, edit=editable, scroll=scrollable, checked=checked_state, " +
                "ch=children, more=off_screen_children_text_summaries."

    override val defaultEnabled: Boolean = true

    override fun configure(config: LocalToolConfig) {
        config.description = description
    }

    override suspend fun invokeRaw(request: BuiltinToolRequest): String {
        return try {
            val result = AccessibilityController.captureScreen()
            result.fold(
                onSuccess = { it.yaml },
                onFailure = { error ->
                    JsonObject(mapOf(
                        "error" to JsonObject(mapOf(
                            "code" to JsonPrimitive("SERVICE_UNAVAILABLE"),
                            "message" to JsonPrimitive(error.message ?: "Unknown error"),
                        )),
                    )).toString()
                },
            )
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            JsonObject(mapOf(
                "error" to JsonObject(mapOf(
                    "code" to JsonPrimitive("INTERNAL_ERROR"),
                    "message" to JsonPrimitive(throwable.message ?: "Unknown error"),
                )),
            )).toString()
        }
    }
}
