package com.tricreta.zafiro.autonomy

import com.tricreta.zafiro.autonomy.form.AgentDraft
import com.tricreta.zafiro.autonomy.form.AgentDraftResult
import com.tricreta.zafiro.autonomy.form.AgentField
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AgentDraftTest {
    private fun result(d: AgentDraft, existing: List<String> = emptyList(), own: String? = null) =
        d.toEntity("id", null, 5L, existing, own)

    @Test
    fun `valid draft becomes a trimmed entity with normalised lists`() {
        val r = assertIs<AgentDraftResult.Ok>(
            result(AgentDraft(name = " Poster ", instructions = " Write posts. ", tools = "http_request; notify\nwait notify", skills = "mkt-copywriting, ")),
        )
        assertEquals("Poster", r.agent.name)
        assertEquals("Write posts.", r.agent.instructions)
        assertEquals("http_request,notify,wait", r.agent.tools)
        assertEquals("mkt-copywriting", r.agent.skills)
        assertEquals(5L, r.agent.createdAtMs)
    }

    @Test
    fun `name and instructions are required and names are unique`() {
        assertEquals(AgentField.NAME, assertIs<AgentDraftResult.Invalid>(result(AgentDraft(name = "", instructions = "x"))).field)
        assertEquals(AgentField.INSTRUCTIONS, assertIs<AgentDraftResult.Invalid>(result(AgentDraft(name = "Poster", instructions = "  "))).field)
        assertEquals(AgentField.NAME, assertIs<AgentDraftResult.Invalid>(result(AgentDraft(name = "poster", instructions = "x"), existing = listOf("Poster"))).field)
        assertIs<AgentDraftResult.Ok>(result(AgentDraft(name = "poster", instructions = "x"), existing = listOf("Poster"), own = "Poster"))
    }

    @Test
    fun `editing round trips`() {
        val a = (result(AgentDraft(name = "Poster", instructions = "i", tools = "a,b", usesScreen = true)) as AgentDraftResult.Ok).agent
        val back = AgentDraft.from(a)
        assertEquals(listOf("a", "b"), AgentDraft.splitList(back.tools))
        assertEquals(true, back.usesScreen)
    }
}
