package com.niki914.nexus.agentic.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class SkillFileRepositoryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun listAll_returnsOneAndTwoLevelSkillsSortedWithMetadataAndEnabledState() {
        val skillsRoot = temporaryFolder.newFolder("skills")
        writeSkill(skillsRoot, "skill-b", skillContent(name = "Shared", description = "Second"))
        writeSkill(
            skillsRoot,
            "group-a/skill-a",
            skillContent(name = "Shared", description = "First")
        )
        writeSkill(skillsRoot, "a/b/c", skillContent(name = "Ignored", description = "Too deep"))
        File(skillsRoot, "status.json").writeText("""{"enabled":{"skill-b":false}}""")

        val skills = SkillFileRepository(skillsRoot).listAll()

        assertEquals(listOf("group-a/skill-a", "skill-b"), skills.map { it.id })
        assertEquals(listOf("Shared", "Shared"), skills.map { it.name })
        assertEquals(listOf("First", "Second"), skills.map { it.description })
        assertEquals(
            listOf("group-a/skill-a/SKILL.md", "skill-b/SKILL.md"),
            skills.map { it.relativePath })
        assertEquals(listOf(true, false), skills.map { it.enabled })
        assertTrue(skills.all { File(it.absolutePath).isFile })
    }

    @Test
    fun listEnabled_filtersDisabledSkills() {
        val skillsRoot = temporaryFolder.newFolder("skills")
        writeSkill(skillsRoot, "skill-a", skillContent(name = "A"))
        writeSkill(skillsRoot, "skill-b", skillContent(name = "B"))
        val repository = SkillFileRepository(skillsRoot)

        val validation = repository.setEnabled("skill-b", false)

        assertNull(validation)
        assertEquals(listOf("skill-a"), repository.listEnabled().map { it.id })
    }

    @Test
    fun load_returnsDetailAndMissingOrInvalidIdsReturnNull() {
        val skillsRoot = temporaryFolder.newFolder("skills")
        val content = skillContent(name = "Skill A", description = "Loads detail")
        writeSkill(skillsRoot, "skill-a", content)

        val repository = SkillFileRepository(skillsRoot)
        val detail = repository.load("skill-a")

        assertEquals("skill-a", detail?.id)
        assertEquals("Skill A", detail?.name)
        assertEquals(content, detail?.content)
        assertNull(repository.load("missing"))
        assertNull(repository.load("../escape"))
    }

    @Test
    fun saveContent_updatesOnlyTargetSkillAndReturnsValidationForMissingOrInvalidIds() {
        val skillsRoot = temporaryFolder.newFolder("skills")
        val originalA = skillContent(name = "A")
        val originalB = skillContent(name = "B")
        val fileA = writeSkill(skillsRoot, "skill-a", originalA)
        val fileB = writeSkill(skillsRoot, "skill-b", originalB)
        val updatedA = skillContent(name = "A2", description = "Updated")
        val repository = SkillFileRepository(skillsRoot)

        val validation = repository.saveContent("skill-a", updatedA)

        assertNull(validation)
        assertEquals(updatedA, fileA.readText())
        assertEquals(originalB, fileB.readText())
        assertNotNull(repository.saveContent("missing", updatedA))
        assertNotNull(repository.saveContent("a/b/c", updatedA))
    }

    @Test
    fun saveContent_rejectsSymlinkAliasToAnotherSkill() {
        val skillsRoot = temporaryFolder.newFolder("skills")
        val originalB = skillContent(name = "B")
        val fileB = writeSkill(skillsRoot, "skill-b", originalB)
        val aliasDir = File(skillsRoot, "skill-a")
        aliasDir.mkdirs()
        Files.createSymbolicLink(File(aliasDir, "SKILL.md").toPath(), fileB.toPath())
        val repository = SkillFileRepository(skillsRoot)

        val validation = repository.saveContent("skill-a", skillContent(name = "A2"))

        assertNotNull(validation)
        assertEquals(originalB, fileB.readText())
    }

    @Test
    fun statusJsonId_returnsValidationInsteadOfCollidingWithStateFile() {
        val skillsRoot = temporaryFolder.newFolder("skills")
        val statusDir = File(skillsRoot, "status.json")
        statusDir.mkdirs()
        File(statusDir, "SKILL.md").writeText(skillContent(name = "Invalid"))
        val repository = SkillFileRepository(skillsRoot)

        assertEquals(emptyList<String>(), repository.listAll().map { it.id })
        assertNotNull(repository.setEnabled("status.json", false))
        assertNotNull(repository.delete("status.json"))
    }

    @Test
    fun setEnabled_returnsValidationForMissingOrInvalidIds() {
        val skillsRoot = temporaryFolder.newFolder("skills")
        writeSkill(skillsRoot, "skill-a", skillContent(name = "A"))
        val repository = SkillFileRepository(skillsRoot)

        assertNull(repository.setEnabled("skill-a", false))
        assertFalse(repository.load("skill-a")?.enabled ?: true)
        assertNotNull(repository.setEnabled("missing", false))
        assertNotNull(repository.setEnabled("a/b/c", false))
    }

    @Test
    fun delete_removesOnlyTargetSkillAndCleansEmptySkillDirectories() {
        val skillsRoot = temporaryFolder.newFolder("skills")
        val outside = temporaryFolder.newFile("outside.txt")
        outside.writeText("keep")
        val target = writeSkill(skillsRoot, "group-a/skill-a", skillContent(name = "A"))
        val sibling = writeSkill(skillsRoot, "group-a/skill-b", skillContent(name = "B"))
        val repository = SkillFileRepository(skillsRoot)
        repository.setEnabled("group-a/skill-a", false)

        val validation = repository.delete("group-a/skill-a")

        assertNull(validation)
        assertFalse(target.exists())
        assertTrue(sibling.exists())
        assertTrue(skillsRoot.exists())
        assertEquals("keep", outside.readText())
        assertTrue(repository.load("group-a/skill-a") == null)
        assertTrue(
            SkillEnabledStateStore(
                File(
                    skillsRoot,
                    "status.json"
                )
            ).isEnabled("group-a/skill-a")
        )
        assertNotNull(repository.delete("missing"))
        assertNotNull(repository.delete("../escape"))
    }

    private fun writeSkill(root: File, id: String, content: String): File {
        val file = File(root, "$id/SKILL.md")
        file.parentFile?.mkdirs()
        file.writeText(content)
        return file
    }

    private fun skillContent(
        name: String,
        description: String = "",
    ): String {
        return """
            ---
            name: $name
            description: $description
            ---

            Body for $name
        """.trimIndent()
    }
}
