package com.niki914.nexus.agentic.chat.agentic.buildin.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenOperationArgsTest {

    @Test
    fun parse_read_defaultDelay() {
        val result = parseArguments("""{"operation": "read"}""")
        assertTrue(result.isSuccess)
        val args = result.getOrThrow()
        assertTrue(args.operation is ScreenOp.Read)
        assertEquals(1000L, args.delayMs)
    }

    @Test
    fun parse_read_customDelay() {
        val result = parseArguments("""{"operation": "read", "delay_ms": 1000}""")
        assertTrue(result.isSuccess)
        val args = result.getOrThrow()
        assertTrue(args.operation is ScreenOp.Read)
        assertEquals(1000L, args.delayMs)
    }

    @Test
    fun parse_tap() {
        val result = parseArguments("""{"operation": "tap", "token": "a3f2_42"}""")
        assertTrue(result.isSuccess)
        val args = result.getOrThrow()
        assertTrue(args.operation is ScreenOp.Tap)
        assertEquals("a3f2_42", (args.operation as ScreenOp.Tap).token)
        assertEquals(1000L, args.delayMs)
    }

    @Test
    fun parse_setText() {
        val result = parseArguments(
            """{"operation": "set_text", "token": "a3f2_7", "text": "hello"}"""
        )
        assertTrue(result.isSuccess)
        val args = result.getOrThrow()
        assertTrue(args.operation is ScreenOp.SetText)
        val setText = args.operation as ScreenOp.SetText
        assertEquals("a3f2_7", setText.token)
        assertEquals("hello", setText.text)
    }

    @Test
    fun parse_search() {
        val result = parseArguments("""{"operation": "search", "keywords": ["settings"]}""")
        assertTrue(result.isSuccess)
        val args = result.getOrThrow()
        assertTrue(args.operation is ScreenOp.Search)
        val search = args.operation as ScreenOp.Search
        assertEquals(listOf("settings"), search.keywords)
        assertEquals("any", search.matchMode)
        assertEquals(10, search.limit)
    }

    @Test
    fun parse_searchWithAllParams() {
        val result = parseArguments(
            """{"operation": "search", "keywords": ["a", "b"], "match_mode": "all", "limit": 5}"""
        )
        assertTrue(result.isSuccess)
        val args = result.getOrThrow()
        assertTrue(args.operation is ScreenOp.Search)
        val search = args.operation as ScreenOp.Search
        assertEquals(listOf("a", "b"), search.keywords)
        assertEquals("all", search.matchMode)
        assertEquals(5, search.limit)
    }

    @Test
    fun parse_shellTap() {
        val result = parseArguments("""{"operation": "shell_tap", "x": 100, "y": 200}""")
        assertTrue(result.isSuccess)
        val args = result.getOrThrow()
        assertTrue(args.operation is ScreenOp.ShellTap)
        val shellTap = args.operation as ScreenOp.ShellTap
        assertEquals(100, shellTap.x)
        assertEquals(200, shellTap.y)
    }

    @Test
    fun parse_shellSwipe() {
        val result = parseArguments(
            """{"operation": "shell_swipe", "start_x": 0, "start_y": 1000, "end_x": 0, "end_y": 200}"""
        )
        assertTrue(result.isSuccess)
        val args = result.getOrThrow()
        assertTrue(args.operation is ScreenOp.ShellSwipe)
        val swipe = args.operation as ScreenOp.ShellSwipe
        assertEquals(0, swipe.startX)
        assertEquals(1000, swipe.startY)
        assertEquals(0, swipe.endX)
        assertEquals(200, swipe.endY)
        assertEquals(300L, swipe.duration)
    }

    @Test
    fun parse_shellKey() {
        val result = parseArguments("""{"operation": "shell_key", "code": 4}""")
        assertTrue(result.isSuccess)
        val args = result.getOrThrow()
        assertTrue(args.operation is ScreenOp.ShellKey)
        assertEquals(4, (args.operation as ScreenOp.ShellKey).code)
    }

    @Test
    fun parse_missingOperation_fails() {
        val result = parseArguments("""{}""")
        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull()?.message?.contains("Missing required field: operation.") == true
        )
    }

    @Test
    fun parse_unknownOperation_fails() {
        val result = parseArguments("""{"operation": "unknown_op"}""")
        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull()?.message?.contains("Unknown operation") == true
        )
    }

    @Test
    fun parse_tapWithoutToken_fails() {
        val result = parseArguments("""{"operation": "tap"}""")
        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull()?.message?.contains("Missing required field: token") == true
        )
    }

    @Test
    fun parse_invalidJson_fails() {
        val result = parseArguments("not json at all")
        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull()?.message?.contains("Invalid JSON") == true
        )
    }

    @Test
    fun parse_shellTapMissingX_fails() {
        val result = parseArguments("""{"operation": "shell_tap", "y": 200}""")
        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull()?.message?.contains("x for operation 'shell_tap'") == true
        )
    }
}
