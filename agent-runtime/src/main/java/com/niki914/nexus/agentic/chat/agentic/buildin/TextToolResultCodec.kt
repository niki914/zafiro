package com.niki914.nexus.agentic.chat.agentic.buildin

import com.niki914.nexus.agentic.chat.agentic.buildin.TextToolResult.Status
import java.nio.charset.StandardCharsets

object TextToolResultCodec {

    const val CODE_MALFORMED = "MALFORMED_TOOL_RESULT"
    private const val MAX_HEADER_LINES = 16
    private const val MAX_HEADER_BYTES = 4096

    /**
     * Encodes a [TextToolResult] into the text protocol format:
     * ```
     * #!tool-result
     * #!status: success|failure
     * #!code: ERROR_CODE       (failure only, optional)
     * #!message: Human message  (failure only, optional)
     *
     * <payload>
     * ```
     *
     * Rules:
     * - Header lines start with "#!"
     * - One blank line separates header from payload
     * - message newlines/carriage-returns/tabs are folded to spaces
     * - Empty optional fields are NOT emitted
     * - Payload is preserved verbatim (no trim, re-indent, or escaping)
     * - If the encoded header exceeds 4096 UTF-8 bytes, the message field
     *   is progressively truncated (with "…" appended) until the
     *   header fits. This guarantees encode() output is always decodable
     *   by decode().
     */
    fun encode(result: TextToolResult): String {
        return encodeHeader(result) + "\n\n" + result.payload
    }

    /**
     * Attempts to decode a text protocol result.
     *
     * @return [TextToolResult] if the first line is exactly "#!tool-result" AND
     *         the header is parseable. Returns null if the first line does not match
     *         (caller should fall back to other classifiers).
     *
     * Returns [TextToolResult] with status=Failure + code=MALFORMED_TOOL_RESULT if:
     * - Header exceeds 16 lines or 4096 UTF-8 bytes
     * - "status" field is missing, invalid, or duplicated
     * - Any header field name is duplicated
     */
    fun decode(raw: String): TextToolResult? {
        // Step 1: Check sentinel
        val firstNewline = raw.indexOf('\n')
        if (firstNewline == -1) return null
        val sentinel = raw.substring(0, firstNewline)
        if (sentinel != "#!tool-result") return null

        val rest = raw.substring(firstNewline + 1)

        // Step 2: Find blank line separator
        val sepIndex = rest.indexOf("\n\n")
        if (sepIndex == -1) {
            return malformed(raw, "Missing blank line separator between header and payload")
        }

        val headerSection = rest.substring(0, sepIndex)
        val payload = rest.substring(sepIndex + 2)

        // Step 3: Validate header size
        val headerLines = headerSection.split("\n")
        if (headerLines.size > MAX_HEADER_LINES) {
            return malformed(raw, "Header exceeds $MAX_HEADER_LINES lines")
        }
        if (headerSection.toByteArray(StandardCharsets.UTF_8).size > MAX_HEADER_BYTES) {
            return malformed(raw, "Header exceeds $MAX_HEADER_BYTES bytes")
        }

        // Step 4: Parse header fields
        val parseResult = parseHeader(headerLines)
        if (parseResult == null) {
            return malformed(raw, "Invalid header format")
        }

        val fields = parseResult

        // Step 5: Validate status
        val statusStr = fields["status"]
            ?: return malformed(raw, "Missing status field")
        val status = when (statusStr.lowercase()) {
            "success" -> Status.Success
            "failure" -> Status.Failure
            else -> return malformed(raw, "Invalid status value: $statusStr")
        }

        // Step 6: Extract known fields; unknown fields are ignored
        val code = fields["code"]
        val message = fields["message"]

        return TextToolResult(status, payload, code, message)
    }

    /**
     * Encodes the header portion: sentinel line plus all header fields.
     * Message newlines/carriage-returns/tabs are folded to spaces.
     * If the encoded header exceeds [MAX_HEADER_BYTES], the message field is
     * progressively truncated with "…" appended until it fits.
     */
    private fun encodeHeader(result: TextToolResult): String {
        val lines = mutableListOf(
            "#!tool-result",
            "#!status: ${result.status.name.lowercase()}",
        )
        if (result.status == Status.Failure) {
            if (!result.code.isNullOrEmpty()) {
                lines.add("#!code: ${result.code}")
            }
            if (!result.message.isNullOrEmpty()) {
                val folded = result.message!!
                    .replace("\r\n", " ")
                    .replace('\r', ' ')
                    .replace('\n', ' ')
                    .replace('\t', ' ')
                lines.add(truncateMessage(lines, folded))
            }
        }
        return lines.joinToString("\n")
    }

    /**
     * Truncates the message value so that the total header (lines joined
     * with "\n") does not exceed [MAX_HEADER_BYTES] UTF-8 bytes.
     * Characters are removed one at a time from the right, with "…"
     * appended, until the limit is satisfied.
     */
    internal fun truncateMessage(prefixLines: List<String>, message: String): String {
        val ellipsis = "…"

        // First try without truncation (no ellipsis when the full message fits)
        val fullLine = "#!message: $message"
        val testLines = prefixLines + fullLine
        if (testLines.joinToString("\n").toByteArray(StandardCharsets.UTF_8).size <= MAX_HEADER_BYTES) {
            return fullLine
        }

        // Progressive truncation: remove last character and append ellipsis
        // The loop always terminates: eventually the message is empty, displayMsg
        // becomes "…" (3 UTF-8 bytes), and the header prefix is well under 4096.
        var truncated = message
        while (true) {
            truncated = truncated.substring(0, truncated.length - 1)
            val displayMsg = "$truncated$ellipsis"
            val line = "#!message: $displayMsg"
            val test = prefixLines + line
            if (test.joinToString("\n").toByteArray(StandardCharsets.UTF_8).size <= MAX_HEADER_BYTES) {
                return line
            }
        }
    }

    /**
     * Parses header lines (after sentinel, before blank line separator)
     * into a map of field key to field value.
     *
     * Returns null if any line is not in "#!key: value" format, or if a key
     * is empty, or if a key is duplicated.
     */
    private fun parseHeader(lines: List<String>): Map<String, String>? {
        val fields = mutableMapOf<String, String>()
        for (line in lines) {
            if (!line.startsWith("#!")) return null
            val content = line.removePrefix("#!")
            val sep = content.indexOf(": ")
            if (sep == -1) return null
            val key = content.substring(0, sep).trim()
            val value = content.substring(sep + 2).trim()
            if (key.isEmpty() || fields.containsKey(key)) return null
            fields[key] = value
        }
        return fields
    }

    private fun malformed(raw: String, reason: String): TextToolResult {
        return TextToolResult(Status.Failure, raw, CODE_MALFORMED, reason)
    }
}
