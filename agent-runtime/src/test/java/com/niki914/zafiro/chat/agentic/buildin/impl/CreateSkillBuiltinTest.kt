package com.niki914.zafiro.chat.agentic.buildin.impl

import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CreateSkillBuiltinTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun call(json: String) = runBlocking { CreateSkillBuiltin { File(tmp.root, "skills") }.invoke(BuiltinToolRequest("create_skill", json)) }

    @Test fun installsASkillWithFrontmatter() {
        val r = call("""{"id":"my-skill","description":"Use when X","body":"# Steps\n1. do","adapted_from":"https://example.com/r"}""")
        assertTrue(r.message, r.ok)
        val text = File(tmp.root, "skills/my-skill/SKILL.md").readText()
        assertTrue(text.startsWith("---\nname: my-skill\ndescription: Use when X\n"))
        assertTrue(text.contains("adapted_from: https://example.com/r")); assertTrue(text.contains("# Steps"))
    }

    @Test fun refusesBadInputAndNeverOverwritesOthersSkills() {
        assertNotNull(SkillFiles.validate("Bad Id", "d", "b")); assertNotNull(SkillFiles.validate("ok-id", "", "b")); assertNotNull(SkillFiles.validate("ok-id", "d", ""))
        assertNull(SkillFiles.validate("ok-id", "d", "b"))
        File(tmp.root, "skills/bundled").also { it.mkdirs() }
        assertFalse(call("""{"id":"bundled","description":"d","body":"b","overwrite":true}""").ok)
        assertTrue(call("""{"id":"mine","description":"d","body":"b"}""").ok)
        assertFalse(call("""{"id":"mine","description":"d","body":"b2"}""").ok)
        assertTrue(call("""{"id":"mine","description":"d","body":"b2","overwrite":true}""").ok)
        assertEquals(true, File(tmp.root, "skills/mine/SKILL.md").readText().contains("b2"))
    }
}
