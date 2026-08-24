package com.niki914.zafiro.chat.agentic.buildin.impl

import com.niki914.zafiro.chat.agentic.accessibility.ScreenSnapshot
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolResult
import com.niki914.zafiro.chat.agentic.buildin.ScreenOperationError
import com.niki914.zafiro.chat.agentic.buildin.TextToolResult
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenOperationAccessibilityBuiltinTest {

    @Test
    fun `assembleActionResult with action failure and capture success returns failure with overridden message and yaml payload`() {
        val actionResult = BuiltinToolResult(
            ok = false,
            code = "VERSION_MISMATCH",
            message = "Token expired, read the screen first to get a fresh token",
            hint = "",
            fieldErrors = emptyMap(),
            data = JsonObject(emptyMap()),
        )
        val captureResult = Result.success(ScreenSnapshot("some yaml tree", "v2", 5))

        val result = assembleActionResult(actionResult, captureResult)

        assertEquals(TextToolResult.Status.Failure, result.status)
        assertEquals("VERSION_MISMATCH", result.code)
        assertNotNull(result.message)
        val msg = result.message!!
        assertTrue(
            "message should contain 'fresh screen tree' but was: $msg",
            msg.contains("fresh screen tree"),
        )
        assertTrue(
            "message should NOT contain 're-read' but was: $msg",
            !msg.contains("re-read"),
        )
        assertTrue(
            "message should NOT contain 'read or search' but was: $msg",
            !msg.contains("read or search"),
        )
        assertEquals("some yaml tree", result.payload)
    }

    @Test
    fun `assembleActionResult with SHELL_TIMEOUT warns not to blindly retry`() {
        val actionResult = BuiltinToolResult(
            ok = false,
            code = ScreenOperationError.SHELL_TIMEOUT.code,
            message = "Shell command timed out after 5000ms",
            hint = "",
            fieldErrors = emptyMap(),
            data = JsonObject(emptyMap()),
        )
        val captureResult = Result.success(ScreenSnapshot("some yaml tree", "v2", 5))

        val result = assembleActionResult(actionResult, captureResult)

        assertEquals(TextToolResult.Status.Failure, result.status)
        assertEquals(ScreenOperationError.SHELL_TIMEOUT.code, result.code)
        val msg = result.message!!
        assertTrue(
            "message should warn against blind retry but was: $msg",
            msg.contains("Do NOT retry") || msg.contains("do not blindly retry"),
        )
        assertTrue(
            "message should mention inspecting the tree but was: $msg",
            msg.contains("inspect the tree"),
        )
        assertEquals("some yaml tree", result.payload)
    }

    @Test
    fun `assembleActionResult with SHELL_SESSION_LOST warns not to blindly retry`() {
        val actionResult = BuiltinToolResult(
            ok = false,
            code = ScreenOperationError.SHELL_SESSION_LOST.code,
            message = "Shell session lost",
            hint = "",
            fieldErrors = emptyMap(),
            data = JsonObject(emptyMap()),
        )
        val captureResult = Result.success(ScreenSnapshot("some yaml tree", "v2", 5))

        val result = assembleActionResult(actionResult, captureResult)

        assertEquals(TextToolResult.Status.Failure, result.status)
        assertEquals(ScreenOperationError.SHELL_SESSION_LOST.code, result.code)
        val msg = result.message!!
        assertTrue(
            "message should warn against blind retry but was: $msg",
            msg.contains("Do NOT retry") || msg.contains("do not blindly retry"),
        )
        assertEquals("some yaml tree", result.payload)
    }

    @Test
    fun `assembleActionResult with action failure and capture failure returns failure with combined error messages`() {
        val actionResult = BuiltinToolResult(
            ok = false,
            code = "NODE_NOT_FOUND",
            message = "Node 42 not found",
            hint = "",
            fieldErrors = emptyMap(),
            data = JsonObject(emptyMap()),
        )
        val captureError = RuntimeException("Accessibility service disconnected")
        val captureResult = Result.failure<ScreenSnapshot>(captureError)

        val result = assembleActionResult(actionResult, captureResult)

        assertEquals(TextToolResult.Status.Failure, result.status)
        assertEquals("NODE_NOT_FOUND", result.code)
        assertNotNull(result.message)
        val msg = result.message!!
        assertTrue("message should contain action error", msg.contains("Node 42 not found"))
        assertTrue(
            "message should contain capture error",
            msg.contains("Accessibility service disconnected"),
        )
    }
}
