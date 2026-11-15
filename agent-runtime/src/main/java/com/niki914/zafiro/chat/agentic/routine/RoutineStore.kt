package com.niki914.zafiro.chat.agentic.routine

import java.io.File

/** Reads the files/routines folder (JSON files). A broken file is reported by name, never skipped silently. */
class RoutineStore(private val dir: File) {
    data class Loaded(val routines: Map<String, RoutineSpec>, val errors: Map<String, String>)

    fun load(): Loaded {
        val ok = LinkedHashMap<String, RoutineSpec>()
        val bad = LinkedHashMap<String, String>()
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.sortedBy { it.name }?.forEach { f ->
            try {
                val spec = RoutineParser.parse(f.readText())
                ok[spec.id] = spec
            } catch (e: RoutineParser.Invalid) {
                bad[f.name] = e.message.orEmpty()
            }
        }
        return Loaded(ok, bad)
    }
}
