package com.niki914.nexus.agentic.chat.agentic.buildin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class TextToolResultCodecTest {

    // -----------------------------------------------------------------------
    // Encode
    // -----------------------------------------------------------------------

    @Test
    fun `encode success with YAML payload`() {
        val result = TextToolResult.success("node1:\n  token: abc_1\n  text: Hello")
        val encoded = TextToolResultCodec.encode(result)
        val expected = "#!tool-result\n#!status: success\n\nnode1:\n  token: abc_1\n  text: Hello"
        assertEquals(expected, encoded)
    }

    @Test
    fun `encode failure with code and message`() {
        val result = TextToolResult.failure("VERSION_MISMATCH", "Token is stale")
        val encoded = TextToolResultCodec.encode(result)
        val expected = "#!tool-result\n#!status: failure\n#!code: VERSION_MISMATCH\n#!message: Token is stale\n\n"
        assertEquals(expected, encoded)
    }

    @Test
    fun `encode failure without payload`() {
        val result = TextToolResult.failure("ERR", "msg")
        val encoded = TextToolResultCodec.encode(result)
        val expected = "#!tool-result\n#!status: failure\n#!code: ERR\n#!message: msg\n\n"
        assertEquals(expected, encoded)
    }

    @Test
    fun `encode message folding for newlines carriage returns and tabs`() {
        val result = TextToolResult.failure("ERR", "line1\nline2\rtab\there")
        val encoded = TextToolResultCodec.encode(result)
        val expected = "#!tool-result\n#!status: failure\n#!code: ERR\n#!message: line1 line2 tab here\n\n"
        assertEquals(expected, encoded)
    }

    @Test
    fun `encode super long message truncated header under 4096 bytes`() {
        val longMsg = "a".repeat(4050)
        val result = TextToolResult.failure("ERR", longMsg)
        val encoded = TextToolResultCodec.encode(result)

        val blankLineIdx = encoded.indexOf("\n\n")
        val header = encoded.substring(0, blankLineIdx)
        val headerBytes = header.toByteArray(StandardCharsets.UTF_8).size
        assertTrue(
            "Header must be ≤ 4096 UTF-8 bytes, was $headerBytes",
            headerBytes <= 4096,
        )

        // Verify truncated message ends with ellipsis
        val messageLine = encoded.lines().first { it.startsWith("#!message: ") }
        val message = messageLine.removePrefix("#!message: ")
        assertTrue("Truncated message must end with …", message.endsWith("…"))
        assertTrue("Truncated message must be shorter than original", message.length < 4050)
    }

    // -----------------------------------------------------------------------
    // Decode
    // -----------------------------------------------------------------------

    @Test
    fun `decode success result`() {
        val raw = "#!tool-result\n#!status: success\n\nnode1:\n  token: abc_1"
        val decoded = TextToolResultCodec.decode(raw)
        assertNotNull(decoded)
        assertEquals(TextToolResult.Status.Success, decoded!!.status)
        assertEquals("node1:\n  token: abc_1", decoded.payload)
        assertNull(decoded.code)
        assertNull(decoded.message)
    }

    @Test
    fun `decode failure with code message and yaml`() {
        val raw = "#!tool-result\n#!status: failure\n#!code: VERSION_MISMATCH\n#!message: Token stale\n\nyaml: content"
        val decoded = TextToolResultCodec.decode(raw)
        assertNotNull(decoded)
        assertEquals(TextToolResult.Status.Failure, decoded!!.status)
        assertEquals("yaml: content", decoded.payload)
        assertEquals("VERSION_MISMATCH", decoded.code)
        assertEquals("Token stale", decoded.message)
    }

    @Test
    fun `decode null on non-protocol first line`() {
        assertNull(TextToolResultCodec.decode("random text"))
        assertNull(TextToolResultCodec.decode("{\"error\":{\"code\":\"X\"}}"))
        assertNull(TextToolResultCodec.decode(""))
        assertNull(TextToolResultCodec.decode("#!other: value"))
    }

    @Test
    fun `decode MALFORMED on missing blank line separator`() {
        val raw = "#!tool-result\n#!status: success\ncontent without blank line"
        val decoded = TextToolResultCodec.decode(raw)
        assertNotNull(decoded)
        assertEquals(TextToolResult.Status.Failure, decoded!!.status)
        assertEquals(TextToolResultCodec.CODE_MALFORMED, decoded.code)
        assertNotNull(decoded.message)
        assertTrue(decoded.message!!.contains("blank line"))
    }

    @Test
    fun `decode MALFORMED on non hashbang line in header`() {
        val raw = "#!tool-result\nbadline\n\npayload"
        val decoded = TextToolResultCodec.decode(raw)
        assertNotNull(decoded)
        assertEquals(TextToolResult.Status.Failure, decoded!!.status)
        assertEquals(TextToolResultCodec.CODE_MALFORMED, decoded.code)
    }

    @Test
    fun `decode MALFORMED on invalid header line format no colon space`() {
        val raw = "#!tool-result\n#!badformat\n\npayload"
        val decoded = TextToolResultCodec.decode(raw)
        assertNotNull(decoded)
        assertEquals(TextToolResult.Status.Failure, decoded!!.status)
        assertEquals(TextToolResultCodec.CODE_MALFORMED, decoded.code)
    }

    @Test
    fun `decode MALFORMED on missing status`() {
        val raw = "#!tool-result\n#!code: X\n\npayload"
        val decoded = TextToolResultCodec.decode(raw)
        assertNotNull(decoded)
        assertEquals(TextToolResult.Status.Failure, decoded!!.status)
        assertEquals(TextToolResultCodec.CODE_MALFORMED, decoded.code)
    }

    @Test
    fun `decode MALFORMED on duplicate status field`() {
        val raw = "#!tool-result\n#!status: success\n#!status: failure\n\npayload"
        val decoded = TextToolResultCodec.decode(raw)
        assertNotNull(decoded)
        assertEquals(TextToolResult.Status.Failure, decoded!!.status)
        assertEquals(TextToolResultCodec.CODE_MALFORMED, decoded.code)
    }

    @Test
    fun `decode MALFORMED on invalid status value`() {
        val raw = "#!tool-result\n#!status: maybe\n\npayload"
        val decoded = TextToolResultCodec.decode(raw)
        assertNotNull(decoded)
        assertEquals(TextToolResult.Status.Failure, decoded!!.status)
        assertEquals(TextToolResultCodec.CODE_MALFORMED, decoded.code)
    }

    @Test
    fun `decode MALFORMED on header exceeding 16 lines`() {
        val headerLines = (1..17).joinToString("\n") { "#!field$it: value" }
        val raw = "#!tool-result\n$headerLines\n\npayload"
        val decoded = TextToolResultCodec.decode(raw)
        assertNotNull(decoded)
        assertEquals(TextToolResult.Status.Failure, decoded!!.status)
        assertEquals(TextToolResultCodec.CODE_MALFORMED, decoded.code)
    }

    @Test
    fun `decode MALFORMED on header exceeding 4096 bytes`() {
        val largeMsg = "x".repeat(4100)
        val raw = "#!tool-result\n#!status: failure\n#!message: $largeMsg\n\npayload"
        val decoded = TextToolResultCodec.decode(raw)
        assertNotNull(decoded)
        assertEquals(TextToolResult.Status.Failure, decoded!!.status)
        assertEquals(TextToolResultCodec.CODE_MALFORMED, decoded.code)
    }

    @Test
    fun `decode with unknown header field succeeds`() {
        val raw = "#!tool-result\n#!status: success\n#!unknown_key: somevalue\n\npayload"
        val decoded = TextToolResultCodec.decode(raw)
        assertNotNull(decoded)
        assertEquals(TextToolResult.Status.Success, decoded!!.status)
        assertEquals("payload", decoded.payload)
        assertNull(decoded.code)
        assertNull(decoded.message)
    }

    // -----------------------------------------------------------------------
    // Roundtrip
    // -----------------------------------------------------------------------

    @Test
    fun `roundtrip encode decode success`() {
        val original = TextToolResult.success("some yaml content")
        val encoded = TextToolResultCodec.encode(original)
        val decoded = TextToolResultCodec.decode(encoded)
        assertNotNull(decoded)
        assertEquals(original.status, decoded!!.status)
        assertEquals(original.payload, decoded.payload)
        assertEquals(original.code, decoded.code)
        assertEquals(original.message, decoded.message)
    }

    @Test
    fun `roundtrip encode decode failure with code message and payload`() {
        val original = TextToolResult.failure("ERR_CODE", "Something went wrong", "yaml payload")
        val encoded = TextToolResultCodec.encode(original)
        val decoded = TextToolResultCodec.decode(encoded)
        assertNotNull(decoded)
        assertEquals(original.status, decoded!!.status)
        assertEquals("yaml payload", decoded.payload)
        assertEquals("ERR_CODE", decoded.code)
        assertEquals("Something went wrong", decoded.message)
    }

    @Test
    fun `roundtrip encode decode truncated message idempotent`() {
        val longMsg = "a".repeat(4050)
        val original = TextToolResult.failure("ERR", longMsg)
        val encoded = TextToolResultCodec.encode(original)
        val decoded = TextToolResultCodec.decode(encoded)
        assertNotNull(decoded)
        assertEquals(TextToolResult.Status.Failure, decoded!!.status)
        assertEquals("ERR", decoded.code)
        assertNotNull(decoded.message)
        assertTrue("Truncated message must end with …", decoded.message!!.endsWith("…"))

        // Re-encode the decoded result; output must be identical
        val reEncoded = TextToolResultCodec.encode(decoded)
        assertEquals("Re-encode of decoded truncation must be idempotent", encoded, reEncoded)
    }
}
