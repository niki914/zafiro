package com.niki914.nexus.agentic.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SkillEnabledStateStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun isEnabled_defaultsToTrueWhenStatusFileIsMissing() {
        val store = SkillEnabledStateStore(statusFile())

        assertTrue(store.isEnabled("skill-a"))
        assertEquals(emptyMap<String, Boolean>(), store.readStates())
    }

    @Test
    fun setEnabled_persistsFalseAndTrueValues() {
        val file = statusFile()

        SkillEnabledStateStore(file).setEnabled("skill-a", false)
        assertFalse(SkillEnabledStateStore(file).isEnabled("skill-a"))

        SkillEnabledStateStore(file).setEnabled("skill-a", true)
        assertTrue(SkillEnabledStateStore(file).isEnabled("skill-a"))
        assertEquals(mapOf("skill-a" to true), SkillEnabledStateStore(file).readStates())
    }

    @Test
    fun readStates_treatsMalformedJsonAsEmptyState() {
        val file = statusFile()
        file.parentFile?.mkdirs()
        file.writeText("""{"enabled":""")

        val store = SkillEnabledStateStore(file)

        assertEquals(emptyMap<String, Boolean>(), store.readStates())
        assertTrue(store.isEnabled("skill-a"))
    }

    @Test
    fun remove_deletesExplicitStateAndRestoresDefaultEnabled() {
        val file = statusFile()
        val store = SkillEnabledStateStore(file)
        store.setEnabled("skill-a", false)

        store.remove("skill-a")

        assertTrue(SkillEnabledStateStore(file).isEnabled("skill-a"))
        assertEquals(emptyMap<String, Boolean>(), SkillEnabledStateStore(file).readStates())
    }

    @Test
    fun setEnabled_writesToProvidedSkillsStatusJsonPath() {
        val skillsRoot = temporaryFolder.newFolder("skills")
        val file = File(skillsRoot, "status.json")

        SkillEnabledStateStore(file).setEnabled("group-a/skill-a", false)

        assertEquals(file.canonicalFile, File(skillsRoot, "status.json").canonicalFile)
        assertTrue(file.readText().contains("group-a/skill-a"))
    }

    private fun statusFile(): File {
        return File(temporaryFolder.newFolder("skills"), "status.json")
    }
}
