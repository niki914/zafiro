package com.niki914.nexus.agentic.chat.agentic.stream

import com.niki914.nexus.agentic.chat.agentic.buildin.TextToolResult
import com.niki914.nexus.agentic.chat.agentic.buildin.TextToolResultCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalToolResultClassifierTest {

    // ── Text protocol (whitelisted toolName) ──────────────────────────────

    @Test
    fun decode_successProtocol_whitelistedTool_returnsSuccessTextProtocol() {
        val encoded = TextToolResultCodec.encode(TextToolResult.success("yaml tree"))
        val parsed = ParsedToolResult.decode(encoded, "screen_operation_accessibility")

        assertEquals(TextToolResult.Status.Success, parsed.status)
        assertEquals(ParsedToolResult.Protocol.TextProtocol, parsed.protocol)
        assertEquals("yaml tree", parsed.payload)
    }

    @Test
    fun decode_failureProtocol_whitelistedTool_returnsFailureTextProtocol() {
        val encoded = TextToolResultCodec.encode(
            TextToolResult.failure("VERSION_MISMATCH", "token expired")
        )
        val parsed = ParsedToolResult.decode(encoded, "screen_operation_shell")

        assertEquals(TextToolResult.Status.Failure, parsed.status)
        assertEquals(ParsedToolResult.Protocol.TextProtocol, parsed.protocol)
        assertEquals("VERSION_MISMATCH", parsed.code)
        assertEquals("token expired", parsed.message)
    }

    @Test
    fun decode_malformedProtocol_whitelistedTool_returnsFailureMalformed() {
        // Missing blank line separator after header
        val raw = "#!tool-result\n#!status: failure\nno blank line separator"
        val parsed = ParsedToolResult.decode(raw, "screen_operation_accessibility")

        assertEquals(TextToolResult.Status.Failure, parsed.status)
        assertEquals(ParsedToolResult.Protocol.TextProtocol, parsed.protocol)
        assertEquals("MALFORMED_TOOL_RESULT", parsed.code)
    }

    // ── Protocol first line but non-whitelisted tool ──────────────────────

    @Test
    fun decode_protocolFirstLine_nonWhitelistedTool_skipsProtocol() {
        // Input starts with "#!tool-result" but tool is "terminal" — not whitelisted
        val raw = "#!tool-result\n#!status: failure\n#!code: X\n\npayload"
        val parsed = ParsedToolResult.decode(raw, "terminal")

        // terminal is not in TEXT_RESULT_TOOL_NAMES → protocol step skipped
        // Not valid JSON → falls through to LegacyText success
        assertEquals(TextToolResult.Status.Success, parsed.status)
        assertEquals(ParsedToolResult.Protocol.LegacyText, parsed.protocol)
        assertEquals(raw, parsed.payload)
    }

    // ── JSON structured error (regression) ────────────────────────────────

    @Test
    fun decode_jsonError_returnsFailureJsonStructured() {
        val raw = """{"error":{"code":"SESSION_NOT_FOUND","message":"Call open first"}}"""
        val parsed = ParsedToolResult.decode(raw, null)

        assertEquals(TextToolResult.Status.Failure, parsed.status)
        assertEquals(ParsedToolResult.Protocol.JsonStructured, parsed.protocol)
        assertEquals("SESSION_NOT_FOUND", parsed.code)
        assertEquals("Call open first", parsed.message)
    }

    // ── Plain YAML / legacy text ──────────────────────────────────────────

    @Test
    fun decode_plainYaml_returnsSuccessLegacyText() {
        val raw = "content:\n  key: value\n"
        val parsed = ParsedToolResult.decode(raw, "screen_operation_accessibility")

        assertEquals(TextToolResult.Status.Success, parsed.status)
        assertEquals(ParsedToolResult.Protocol.LegacyText, parsed.protocol)
        assertEquals(raw, parsed.payload)
    }

    // ── failureMessage regression ─────────────────────────────────────────

    @Test
    fun failureMessage_jsonError_returnsNonNull() {
        val msg = LocalToolResultClassifier.failureMessage(
            """{"error":{"code":"X","message":"Y"}}"""
        )
        assertEquals("Y", msg)
    }

    @Test
    fun failureMessage_null_returnsNull() {
        assertNull(LocalToolResultClassifier.failureMessage(null))
    }

    @Test
    fun failureMessage_blank_returnsNull() {
        assertNull(LocalToolResultClassifier.failureMessage(""))
    }
}
