package com.niki914.nexus.agentic.chat.agentic.buildin.impl

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

sealed class ScreenOp {
    data object Read : ScreenOp()
    data class Tap(val token: String) : ScreenOp()
    data class LongClick(val token: String) : ScreenOp()
    data class ScrollForward(val token: String) : ScreenOp()
    data class ScrollBackward(val token: String) : ScreenOp()
    data class SetText(val token: String, val text: String) : ScreenOp()
    data class Search(
        val keywords: List<String>,
        val matchMode: String = "any",
        val limit: Int = 10,
    ) : ScreenOp()
    // shell ops
    data class ShellTap(val x: Int, val y: Int) : ScreenOp()
    data class ShellLongClick(val x: Int, val y: Int) : ScreenOp()
    data class ShellSwipe(
        val startX: Int,
        val startY: Int,
        val endX: Int,
        val endY: Int,
        val duration: Long = 300,
    ) : ScreenOp()
    data class ShellKey(val code: Int) : ScreenOp()
}

data class ScreenOpArgs(
    val operation: ScreenOp,
    val waitMode: String = "stable",
    val waitMs: Long = 2000,
    val hasExplicitWaitMode: Boolean = false,
)

fun parseArguments(argumentsJson: String): Result<ScreenOpArgs> {
    val element = try {
        Json.parseToJsonElement(argumentsJson)
    } catch (throwable: SerializationException) {
        return Result.failure(
            IllegalArgumentException("Invalid JSON: ${throwable.message}")
        )
    } catch (throwable: IllegalArgumentException) {
        return Result.failure(
            IllegalArgumentException("Invalid JSON: ${throwable.message}")
        )
    }

    val obj = element as? JsonObject
        ?: return Result.failure(
            IllegalArgumentException("argumentsJson must be a JSON object.")
        )

    val operationName = obj["operation"]?.jsonPrimitive?.contentOrNull
        ?: return Result.failure(
            IllegalArgumentException("Missing required field: operation.")
        )

    // Backward compat: old delay_ms field maps to wait_mode "delay"
    val hasExplicitWaitMode = obj.contains("wait_mode") || obj.contains("delay_ms")
    val hasExplicitWaitMs = obj.contains("wait_ms") || obj.contains("delay_ms")
    val waitMode = obj["wait_mode"]?.jsonPrimitive?.contentOrNull
        ?: if (obj.contains("delay_ms")) "delay" else "stable"
    val waitMs = obj["wait_ms"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toLong()
        ?: obj["delay_ms"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toLong()

    if (waitMode != "stable" && waitMode != "delay") {
        return Result.failure(
            IllegalArgumentException("wait_mode must be 'stable' or 'delay', got '$waitMode'")
        )
    }
    if (waitMs != null && (waitMs < 0 || waitMs > 60_000)) {
        return Result.failure(
            IllegalArgumentException("wait_ms must be in range 0..60000, got $waitMs")
        )
    }
    if (waitMode == "delay" && !hasExplicitWaitMs) {
        return Result.failure(
            IllegalArgumentException("wait_ms is required when wait_mode is 'delay'")
        )
    }
    val finalWaitMs = waitMs ?: 2000L

    val operation = when (operationName) {
        "read" -> ScreenOp.Read
        "tap" -> {
            val token = obj["token"]?.jsonPrimitive?.contentOrNull
            if (token != null) {
                ScreenOp.Tap(token)
            } else {
                val x = obj["x"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toInt()
                    ?: return Result.failure(
                        IllegalArgumentException("Missing required field: x for operation 'tap'.")
                    )
                val y = obj["y"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toInt()
                    ?: return Result.failure(
                        IllegalArgumentException("Missing required field: y for operation 'tap'.")
                    )
                ScreenOp.ShellTap(x, y)
            }
        }
        "long_click" -> {
            val token = obj["token"]?.jsonPrimitive?.contentOrNull
            if (token != null) {
                ScreenOp.LongClick(token)
            } else {
                val x = obj["x"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toInt()
                    ?: return Result.failure(
                        IllegalArgumentException("Missing required field: x for operation 'long_click'.")
                    )
                val y = obj["y"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toInt()
                    ?: return Result.failure(
                        IllegalArgumentException("Missing required field: y for operation 'long_click'.")
                    )
                ScreenOp.ShellLongClick(x, y)
            }
        }
        "scroll_forward" -> {
            val token = obj["token"]?.jsonPrimitive?.contentOrNull
                ?: return Result.failure(
                    IllegalArgumentException("Missing required field: token for operation 'scroll_forward'.")
                )
            ScreenOp.ScrollForward(token)
        }
        "scroll_backward" -> {
            val token = obj["token"]?.jsonPrimitive?.contentOrNull
                ?: return Result.failure(
                    IllegalArgumentException("Missing required field: token for operation 'scroll_backward'.")
                )
            ScreenOp.ScrollBackward(token)
        }
        "set_text" -> {
            val token = obj["token"]?.jsonPrimitive?.contentOrNull
                ?: return Result.failure(
                    IllegalArgumentException("Missing required field: token for operation 'set_text'.")
                )
            val text = obj["text"]?.jsonPrimitive?.contentOrNull
                ?: return Result.failure(
                    IllegalArgumentException("Missing required field: text for operation 'set_text'.")
                )
            ScreenOp.SetText(token, text)
        }
        "search" -> {
            val keywordsElement = obj["keywords"]
                ?: return Result.failure(
                    IllegalArgumentException("Missing required field: keywords for operation 'search'.")
                )
            if (keywordsElement !is JsonArray) {
                return Result.failure(
                    IllegalArgumentException("keywords must be a JSON array for operation 'search'.")
                )
            }
            val keywords = keywordsElement.map { it.jsonPrimitive.contentOrNull ?: "" }
                .filter { it.isNotBlank() }
            if (keywords.isEmpty()) {
                return Result.failure(
                    IllegalArgumentException("keywords must be a non-empty array of strings for operation 'search'.")
                )
            }
            val matchMode = obj["match_mode"]?.jsonPrimitive?.contentOrNull ?: "any"
            val limit = obj["limit"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toInt() ?: 10
            ScreenOp.Search(keywords, matchMode, limit)
        }
        "swipe" -> {
            val startX = obj["start_x"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toInt()
                ?: return Result.failure(
                    IllegalArgumentException("Missing or invalid required field: start_x for operation 'swipe'.")
                )
            val startY = obj["start_y"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toInt()
                ?: return Result.failure(
                    IllegalArgumentException("Missing or invalid required field: start_y for operation 'swipe'.")
                )
            val endX = obj["end_x"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toInt()
                ?: return Result.failure(
                    IllegalArgumentException("Missing or invalid required field: end_x for operation 'swipe'.")
                )
            val endY = obj["end_y"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toInt()
                ?: return Result.failure(
                    IllegalArgumentException("Missing or invalid required field: end_y for operation 'swipe'.")
                )
            val duration = obj["duration"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toLong() ?: 300L
            ScreenOp.ShellSwipe(startX, startY, endX, endY, duration)
        }
        "key" -> {
            val code = obj["code"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toInt()
                ?: return Result.failure(
                    IllegalArgumentException("Missing or invalid required field: code for operation 'key'.")
                )
            ScreenOp.ShellKey(code)
        }
        else -> return Result.failure(
            IllegalArgumentException("Unknown operation: '$operationName'.")
        )
    }

    return Result.success(ScreenOpArgs(operation, waitMode, finalWaitMs, hasExplicitWaitMode))
}
