package com.tricreta.zafiro.autonomy.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentsTest {
    @Test
    fun `names are validated and must be unique ignoring case`() {
        assertNull(AgentNames.validate("Researcher", emptyList()))
        assertNull(AgentNames.validate("sales-bot 2", listOf("Researcher")))
        assertNotNull(AgentNames.validate("x", emptyList()))
        assertNotNull(AgentNames.validate("bad/name", emptyList()))
        assertNotNull(AgentNames.validate("researcher", listOf("Researcher")))
        assertNull(AgentNames.validate("researcher", listOf("Researcher"), ownName = "Researcher"))
    }

    @Test
    fun `find ignores case and surrounding spaces`() {
        assertEquals("Social Poster", AgentNames.find("  social poster ", listOf("Researcher", "Social Poster")))
        assertNull(AgentNames.find("nobody", listOf("Researcher")))
    }

    @Test
    fun `brief carries instructions task status protocol and safety rule`() {
        val p = AgentBrief.compose("Poster", "Write posts.", listOf("http_request"), listOf("mkt-copywriting"), "Post about X", delegated = false)
        assertTrue(p.contains("\"Poster\"") && p.contains("Write posts."))
        assertTrue(p.contains("http_request") && p.contains("mkt-copywriting") && p.contains("load_skill"))
        assertTrue(p.contains("STATUS: DONE") && p.contains("take_over"))
        assertTrue(p.trimEnd().endsWith("Post about X"))
        assertFalse(p.contains("called by another agent"))
    }

    @Test
    fun `delegated brief forbids further delegation and empty lists add no restriction lines`() {
        val p = AgentBrief.compose("A", "i", emptyList(), emptyList(), "t", delegated = true)
        assertTrue(p.contains("do not call other agents"))
        assertFalse(p.contains("Use ONLY"))
        assertFalse(p.contains("load_skill"))
    }

    @Test
    fun `history lists at most three runs, truncates, prefers the error`() {
        val runs = List(5) { PastRun("day$it", "FAILED", "s$it", "boom$it") } +
            PastRun("old", "SUCCEEDED", "x".repeat(900), null)
        val text = RunHistory.format(runs)
        assertEquals(3, text.lines().count { it.startsWith("- ") })
        assertTrue(text.contains("boom0") && !text.contains("s0 "))
        assertTrue(RunHistory.format(listOf(PastRun("d", "SUCCEEDED", "y".repeat(900), null))).length < 500)
        assertEquals("", RunHistory.format(emptyList()))
    }

    @Test
    fun `reflection prompt asks for few specific lessons and no secrets`() {
        assertTrue(Reflection.PROMPT.contains("at most 3") && Reflection.PROMPT.contains("secrets"))
    }
}
