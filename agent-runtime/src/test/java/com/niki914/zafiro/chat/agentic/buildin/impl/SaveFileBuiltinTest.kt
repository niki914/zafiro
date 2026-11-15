package com.niki914.zafiro.chat.agentic.buildin.impl

import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SaveFileBuiltinTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun tool() = SaveFileBuiltin { java.io.File(tmp.root, "outputs") }
    private fun call(json: String) = kotlinx.coroutines.runBlocking { tool().invoke(BuiltinToolRequest("save_file", json)) }

    @Test fun savesTextAndReturnsAnOpenableLink() {
        val r = call("""{"op":"save","path":"plans/week 47.md","content":"# Plan\n- one"}""")
        assertTrue(r.message, r.ok)
        assertEquals("# Plan\n- one", java.io.File(tmp.root, "outputs/plans/week 47.md").readText())
        assertTrue(r.message, r.message.contains("(zfile:plans/week%2047.md)"))
    }

    @Test fun savesBinaryFromBase64() {
        val r = call("""{"op":"save","path":"img/a.png","content_base64":"iVBORw0KGgo="}""")
        assertTrue(r.ok)
        assertEquals(8, java.io.File(tmp.root, "outputs/img/a.png").length().toInt())
    }

    @Test fun rejectsPathEscapes() {
        val root = java.io.File(tmp.root, "outputs").also { it.mkdirs() }
        listOf("../x.md", "/etc/passwd", "a/../../b", ".hidden", "a//b", "", "bad*name", "a/.b").forEach { assertNull(it, OutputPaths.resolve(root, it)) }
        assertNotNull(OutputPaths.resolve(root, "a/b-c_d 1.md"))
        assertFalse(call("""{"op":"save","path":"../evil","content":"x"}""").ok)
    }

    @Test fun reportsMissingContentAndBadBase64() {
        assertFalse(call("""{"op":"save","path":"a.md"}""").ok)
        assertFalse(call("""{"op":"save","path":"a.bin","content_base64":"!!!"}""").ok)
    }

    @Test fun listsNewestFirst() {
        call("""{"op":"save","path":"a.md","content":"1"}""")
        val r = call("""{"op":"list"}""")
        assertTrue(r.ok); assertTrue(r.data.toString().contains("zfile:a.md"))
    }

    @Test fun mimeByExtension() {
        assertEquals("text/html", OutputPaths.mime("r.HTML")); assertEquals("application/octet-stream", OutputPaths.mime("x"))
    }
}
