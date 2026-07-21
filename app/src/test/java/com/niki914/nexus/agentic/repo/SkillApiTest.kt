package com.niki914.nexus.agentic.repo

import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SkillApiTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val filesDir: File by lazy { temporaryFolder.newFolder("files") }
    private val context: Context = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = this@SkillApiTest.filesDir
    }

    @After
    fun tearDown() {
        XRepo.resetForTest()
    }

    @Test
    fun skillsApi_usesFilesDirSkillsRootForListAndDetail() = runTest {
        XRepo.init(context)
        writeSkill("skill-a", skillContent(name = "Skill A", description = "From filesDir"))

        val all = XRepo.skills.listAll()
        val detail = XRepo.skills.getDetail("skill-a")

        assertEquals(listOf("skill-a"), all.map { it.id })
        assertEquals("Skill A", all.single().name)
        assertEquals("From filesDir", detail?.description)
        assertEquals(File(filesDir, "skills/skill-a/SKILL.md").canonicalPath, detail?.absolutePath)
    }

    @Test
    fun skillsApi_proxiesSaveSetEnabledAndDeleteWithValidation() = runTest {
        XRepo.init(context)
        val file = writeSkill("skill-a", skillContent(name = "Skill A"))

        assertNull(XRepo.skills.saveContent("skill-a", skillContent(name = "Updated")))
        assertEquals("Updated", XRepo.skills.getDetail("skill-a")?.name)
        assertNull(XRepo.skills.setEnabled("skill-a", false))
        assertEquals(emptyList<String>(), XRepo.skills.listEnabled().map { it.id })
        assertNull(XRepo.skills.delete("skill-a"))
        assertEquals(false, file.exists())

        assertNotNull(XRepo.skills.saveContent("missing", "content"))
        assertNotNull(XRepo.skills.setEnabled("missing", true))
        assertNotNull(XRepo.skills.delete("../escape"))
    }

    private fun writeSkill(id: String, content: String): File {
        val file = File(filesDir, "skills/$id/SKILL.md")
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
