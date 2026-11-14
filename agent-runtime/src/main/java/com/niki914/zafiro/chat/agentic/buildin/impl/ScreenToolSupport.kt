package com.niki914.zafiro.chat.agentic.buildin.impl

import com.niki914.zafiro.api.screen.ActResult
import com.niki914.zafiro.api.screen.Screen
import com.niki914.zafiro.api.screen.ScreenId
import com.niki914.zafiro.api.screen.ScreenSnapshot
import com.niki914.zafiro.api.screen.Screens
import com.niki914.zafiro.api.screen.SemanticToken
import com.niki914.zafiro.api.screen.ShowResult
import com.niki914.zafiro.api.screen.TabInfo
import com.niki914.zafiro.api.screen.Target
import com.niki914.zafiro.chat.agentic.buildin.TextToolResult
import com.niki914.zafiro.service.findService
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal const val SCREEN_SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE"
internal const val SCREEN_UNKNOWN = "UNKNOWN_SCREEN"
internal const val SCREEN_UNSUPPORTED = "UNSUPPORTED_ON_SCREEN"
internal const val SCREEN_INVALID_ARGUMENTS = "INVALID_ARGUMENTS"
internal const val SCREEN_INVALID_JSON = "INVALID_ARGUMENTS_JSON"
internal const val SCREEN_INVALID_OPERATION = "INVALID_OPERATION"
internal const val SCREEN_AMBIGUOUS_APP = "AMBIGUOUS_APP_MATCH"

internal fun screenDirectory(): Screens? = findService()

internal fun parseScreenObject(raw: String): JsonObject? {
    val element = try {
        Json.parseToJsonElement(raw)
    } catch (throwable: SerializationException) {
        return null
    } catch (throwable: IllegalArgumentException) {
        return null
    }
    return element as? JsonObject
}

internal fun JsonObject.text(key: String): String? {
    return this[key].asText()?.trim()?.takeIf { it.isNotEmpty() }
}

internal fun JsonObject.int(key: String): Int? {
    val raw = this[key].asText() ?: return null
    return raw.toIntOrNull() ?: raw.toDoubleOrNull()?.toInt()
}

internal fun JsonObject.long(key: String): Long? {
    val raw = this[key].asText() ?: return null
    return raw.toLongOrNull() ?: raw.toDoubleOrNull()?.toLong()
}

internal fun JsonObject.strings(key: String): List<String>? {
    val array = this[key] as? JsonArray ?: return null
    return array.mapNotNull { item -> item.asText()?.trim()?.takeIf { it.isNotEmpty() } }
}

internal fun JsonObject.screenId(): ScreenId? = text("screen")?.let { ScreenId(it) }

internal fun parseToken(raw: String): SemanticToken? {
    val split = raw.lastIndexOf('_')
    if (split <= 0 || split == raw.lastIndex) return null
    val version = raw.substring(0, split)
    val index = raw.substring(split + 1).toIntOrNull() ?: return null
    if (version.isEmpty()) return null
    return SemanticToken(version, index)
}

internal fun JsonObject.target(): Target? {
    val token = text("token")
    if (token != null) return parseToken(token)?.let { Target.Node(it) }
    val x = int("x")
    val y = int("y")
    if (x != null && y != null) return Target.Point(x, y)
    return null
}

internal fun missingService(): TextToolResult {
    return TextToolResult.failure(
        code = SCREEN_SERVICE_UNAVAILABLE,
        message = "Screen service is not installed.",
    )
}

internal fun unknownScreen(id: ScreenId): TextToolResult {
    return TextToolResult.failure(
        code = SCREEN_UNKNOWN,
        message = "Unknown screen '${id.value}'.",
    )
}

internal fun invalidArguments(message: String): TextToolResult {
    return TextToolResult.failure(code = SCREEN_INVALID_ARGUMENTS, message = message)
}

internal suspend fun Screens.require(id: ScreenId): Screen? = get(id)

internal fun actToText(result: ActResult): TextToolResult {
    return when (result) {
        is ActResult.Done -> TextToolResult.success(payload(result.snapshot, result.tabs))
        is ActResult.Unsupported -> TextToolResult.failure(
            code = SCREEN_UNSUPPORTED,
            message = "This screen does not support ${result.action}.",
        )
        is ActResult.Failed -> TextToolResult.failure(
            code = result.code,
            message = result.code,
            payload = result.snapshot?.yaml.orEmpty(),
        )
    }
}

internal fun showToText(result: ShowResult): TextToolResult {
    return when (result) {
        is ShowResult.Shown -> TextToolResult.success(result.message.orEmpty())
        is ShowResult.Ambiguous -> TextToolResult.failure(
            code = SCREEN_AMBIGUOUS_APP,
            message = "Multiple apps match '${result.name}'.",
            payload = result.candidates.joinToString("\n") { hit ->
                "${hit.packageName}\t${hit.label}"
            },
        )
        is ShowResult.Unsupported -> TextToolResult.failure(
            code = SCREEN_UNSUPPORTED,
            message = "This screen does not support opening ${result.target}.",
        )
        is ShowResult.Failed -> TextToolResult.failure(
            code = result.code,
            message = result.code,
        )
    }
}

private fun payload(snapshot: ScreenSnapshot, tabs: List<TabInfo>?): String {
    if (tabs == null) return snapshot.yaml
    val lines = tabs.joinToString("\n") { tab ->
        "${tab.tabId}\t${tab.active}\t${tab.url}\t${tab.title}"
    }
    return snapshot.yaml + "\n---\n" + lines
}

private fun JsonElement?.asText(): String? {
    return (this as? JsonPrimitive)?.contentOrNull
}
