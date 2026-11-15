package com.tricreta.zafiro.autonomy

import com.tricreta.zafiro.autonomy.workspace.WorkspaceDef
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertTrue

/** Every bundled workspace must parse and only reference skills that are actually bundled. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BundledWorkspacesTest {
    private val assets = File("../../app/src/main/assets")

    @Test fun everyBundledWorkspaceParsesAndReferencesRealSkills() {
        val files = File(assets, "workspaces").listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        assertTrue(files.size >= 13, "expected the bundled workspaces, found ${files.size}")
        files.forEach { f ->
            val def = WorkspaceDef.parse(f.readText())
            assertTrue(def.id == f.nameWithoutExtension, "${f.name}: id must match the file name")
            if (def.id != "toolsmith") assertTrue(def.agents.size >= 8 && def.tasks.size >= 9, "${def.id}: a full team and schedule")
            (def.skills + def.agents.flatMap { it.skills }).distinct().forEach { s ->
                assertTrue(File(assets, "skills/$s/SKILL.md").isFile, "${def.id}: skill $s is not bundled")
            }
            def.tasks.forEach { t -> assertTrue(t.maxMinutes in 5..120 && t.maxSteps in 10..200, "${def.id}/${t.key}: sane budget") }
        }
    }
}
