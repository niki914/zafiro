package com.niki914.zafiro.chat.agentic.routine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** One step of a screen routine. [op]: open_app | tap | type | wait | scroll | back | key | collect | sleep. */
data class RoutineStep(
    val op: String,
    /** Visible texts or content descriptions to look for, in priority order. */
    val labels: List<String> = emptyList(),
    /** After the step at least one of these must be on screen (polled). */
    val expectAny: List<String> = emptyList(),
    /** After the step none of these may remain on screen. */
    val expectGone: List<String> = emptyList(),
    val optional: Boolean = false,
    val text: String = "",
    val packageName: String = "",
    val count: Int = 1,
    val timeoutS: Int = 8,
    val code: Int = 0,
    val ms: Long = 0,
    val note: String = "",
)

data class RoutineParam(val name: String, val required: Boolean)

data class RoutineSpec(
    val id: String,
    val version: Int,
    val platform: String,
    val description: String,
    val params: List<RoutineParam>,
    val steps: List<RoutineStep>,
)

/** Routines are data (JSON in files/routines/), so a wrong screen label is fixed by editing a file, not by rebuilding the app. */
object RoutineParser {
    class Invalid(message: String) : IllegalArgumentException(message)

    private val OPS = setOf("open_app", "tap", "type", "wait", "scroll", "back", "key", "collect", "sleep")

    fun parse(raw: String): RoutineSpec {
        val o = try { Json.parseToJsonElement(raw) as? JsonObject } catch (e: Exception) { null } ?: throw Invalid("Routine is not a JSON object.")
        val id = o.str("id").ifBlank { throw Invalid("Routine needs an id.") }
        val steps = (o["steps"] as? JsonArray ?: throw Invalid("Routine $id needs steps.")).map { parseStep(it.jsonObject, id) }
        if (steps.isEmpty()) throw Invalid("Routine $id has no steps.")
        val params = (o["params"] as? JsonArray).orEmpty().map {
            val p = it.jsonObject
            RoutineParam(p.str("name"), (p["required"] as? JsonPrimitive)?.booleanOrNull ?: true)
        }
        return RoutineSpec(id, (o["version"] as? JsonPrimitive)?.intOrNull ?: 1, o.str("platform"), o.str("description"), params, steps)
    }

    fun parseSteps(raw: JsonArray): List<RoutineStep> = raw.map { parseStep(it.jsonObject, "adhoc") }

    private fun parseStep(s: JsonObject, id: String): RoutineStep {
        val op = s.str("op")
        if (op !in OPS) throw Invalid("Routine $id: unknown op '$op' (use ${OPS.joinToString()}).")
        val step = RoutineStep(
            op = op, labels = s.list("labels"), expectAny = s.list("expect_any"), expectGone = s.list("expect_gone"),
            optional = (s["optional"] as? JsonPrimitive)?.booleanOrNull ?: false, text = s.str("text"), packageName = s.str("package"),
            count = (s["count"] as? JsonPrimitive)?.intOrNull ?: 1, timeoutS = (s["timeout_s"] as? JsonPrimitive)?.intOrNull ?: 8,
            code = (s["code"] as? JsonPrimitive)?.intOrNull ?: 0, ms = ((s["ms"] as? JsonPrimitive)?.contentOrNull)?.toLongOrNull() ?: 0, note = s.str("note"),
        )
        if (op == "tap" && step.labels.isEmpty()) throw Invalid("Routine $id: tap needs labels.")
        if (op == "open_app" && step.packageName.isBlank()) throw Invalid("Routine $id: open_app needs package.")
        if (op == "type" && step.text.isEmpty()) throw Invalid("Routine $id: type needs text.")
        if (op == "wait" && step.labels.isEmpty()) throw Invalid("Routine $id: wait needs labels.")
        return step
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    private fun JsonObject.list(k: String) = (this[k] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }
}
