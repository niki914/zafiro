package com.niki914.nexus.agentic.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class SkillPathResolverTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun scanSkillFiles_returnsOnlyOneAndTwoLevelSkillMarkdownFilesSortedById() {
        val skillsRoot = temporaryFolder.newFolder("skills")
        writeSkill(skillsRoot, "skill-b")
        writeSkill(skillsRoot, "group-a/skill-a")
        writeSkill(skillsRoot, "a/b/c")
        File(skillsRoot, "status.json").writeText("""{"enabled":{}}""")
        File(skillsRoot, "skill-b/README.md").writeText("# ignored")

        val results = SkillPathResolver(skillsRoot).scanSkillFiles()

        assertEquals(listOf("group-a/skill-a", "skill-b"), results.map { it.id })
        assertEquals(
            listOf("group-a/skill-a/SKILL.md", "skill-b/SKILL.md"),
            results.map { it.relativePath },
        )
    }

    @Test
    fun resolveSkillFile_acceptsOneAndTwoLevelIds() {
        val skillsRoot = temporaryFolder.newFolder("skills")
        val resolver = SkillPathResolver(skillsRoot)

        val oneLevel = resolver.resolveSkillFile("skill-a")
        val twoLevel = resolver.resolveSkillFile("group-a/skill-a")

        assertEquals("skill-a/SKILL.md", (oneLevel as SkillPathResolution.Resolved).relativePath)
        assertEquals(
            "group-a/skill-a/SKILL.md",
            (twoLevel as SkillPathResolution.Resolved).relativePath
        )
    }

    @Test
    fun validateId_rejectsUnsafeIds() {
        val resolver = SkillPathResolver(temporaryFolder.newFolder("skills"))
        val invalidIds = listOf(
            "",
            "   ",
            "/absolute",
            ".",
            "..",
            "group/..",
            "group/.",
            "group\\skill",
            "a/b/c",
            "a//b",
            "status.json",
            "status.json/skill-a",
        )

        invalidIds.forEach { id ->
            assertNotNull("Expected invalid id: $id", resolver.validateId(id))
            assertTrue(resolver.resolveSkillFile(id) is SkillPathResolution.Invalid)
        }
    }

    @Test
    fun resolveSkillFile_rejectsCanonicalEscapeThroughSymlink() {
        val skillsRoot = temporaryFolder.newFolder("skills")
        val outside = temporaryFolder.newFolder("outside")
        writeSkill(outside, "escape")
        Files.createSymbolicLink(File(skillsRoot, "link").toPath(), outside.toPath())

        val result = SkillPathResolver(skillsRoot).resolveSkillFile("link/escape")

        assertTrue(result is SkillPathResolution.Invalid)
    }

    @Test
    fun resolveSkillFile_rejectsSymlinkAliasesInsideSkillsRoot() {
        val skillsRoot = temporaryFolder.newFolder("skills")
        val realFile = writeSkill(skillsRoot, "skill-b")
        val aliasDir = File(skillsRoot, "skill-a")
        aliasDir.mkdirs()
        Files.createSymbolicLink(File(aliasDir, "SKILL.md").toPath(), realFile.toPath())

        val result = SkillPathResolver(skillsRoot).resolveSkillFile("skill-a")

        assertTrue(result is SkillPathResolution.Invalid)
    }

    private fun writeSkill(root: File, id: String): File {
        val file = File(root, "$id/SKILL.md")
        file.parentFile?.mkdirs()
        file.writeText("# $id")
        return file
    }
}
