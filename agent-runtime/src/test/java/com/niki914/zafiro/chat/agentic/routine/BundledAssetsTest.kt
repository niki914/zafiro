package com.niki914.zafiro.chat.agentic.routine

import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BundledAssetsTest {
    private val assets = File("../app/src/main/assets")

    @Test
    fun everyBundledRoutineParses() {
        val files = File(assets, "routines").listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        assertTrue("expected bundled routines", files.size >= 40)
        files.forEach { f ->
            val spec = RoutineParser.parse(f.readText())
            assertTrue("${f.name}: id must match file name", spec.id == f.nameWithoutExtension)
        }
    }

    @Test
    fun workspaceAgentsOnlyUseRealTools() {
        val known = BuiltinToolRegistry.default().all().map { it.name }.toSet()
        File(assets, "workspaces").listFiles { f -> f.name.endsWith(".json") }.orEmpty().forEach { f ->
            Json.parseToJsonElement(f.readText()).jsonObject["agents"]!!.jsonArray.forEach { a ->
                a.jsonObject["tools"]!!.jsonArray.map { it.jsonPrimitive.content }.forEach { t ->
                    assertTrue("${f.name}: unknown tool $t", t in known)
                }
            }
        }
    }
}
