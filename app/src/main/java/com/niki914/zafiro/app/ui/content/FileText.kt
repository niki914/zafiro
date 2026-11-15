package com.niki914.zafiro.app.ui.content

import java.io.File
import java.util.zip.ZipFile

/** Turns any file into something readable inside the app. Pure (java.io / java.util.zip only), so it is unit-tested. */
object FileText {
    private val TAG = Regex("(?s)<[^>]+>")

    private fun decodeXml(s: String) = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")

    /** Paragraph/row/slide breaks become newlines, other tags vanish. */
    fun xmlText(xml: String): String = decodeXml(TAG.replace(xml.replace(Regex("</(w:p|a:p|row|si|text:p)>"), "\n").replace(Regex("<w:tab/>"), "\t"), ""))
        .lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")

    /** docx / xlsx / pptx / odt / ods / odp as plain text. Null when the file is not such a package. */
    fun office(file: File, maxChars: Int = 120_000): String? = runCatching {
        ZipFile(file).use { z ->
            val names = z.entries().asSequence().map { it.name }.toList()
            val parts = when {
                "word/document.xml" in names -> listOf("word/document.xml")
                "xl/sharedStrings.xml" in names -> listOf("xl/sharedStrings.xml")
                names.any { it.startsWith("ppt/slides/slide") } -> names.filter { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }.sortedBy { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
                "content.xml" in names -> listOf("content.xml")
                else -> return null
            }
            parts.joinToString("\n\n") { n -> z.getInputStream(z.getEntry(n)).bufferedReader().use { xmlText(it.readText()) } }.take(maxChars)
        }
    }.getOrNull()

    /** Entry list of a zip-like archive: "name (size)". */
    fun archiveListing(file: File): List<String>? = runCatching {
        ZipFile(file).use { z -> z.entries().asSequence().take(500).map { "${it.name}  (${it.size.coerceAtLeast(0) / 1024 + 1} KB)" }.toList() }
    }.getOrNull()

    fun csvRows(text: String, limit: Int = 500): List<List<String>> = text.lineSequence().filter { it.isNotBlank() }.take(limit).map { line ->
        val delim = if (line.count { it == '\t' } > line.count { it == ',' }) '\t' else ','
        val out = mutableListOf<String>(); val sb = StringBuilder(); var q = false
        for (c in line) when { c == '"' -> q = !q; c == delim && !q -> { out += sb.toString().trim(); sb.clear() }; else -> sb.append(c) }
        out += sb.toString().trim(); out
    }.toList()

    /** Text if the bytes look like text, otherwise a hex dump of the first bytes, so every file type can be opened. */
    fun bytesPreview(file: File, hexBytes: Int = 2048): String {
        val head = file.inputStream().use { it.readNBytesCompat(65_536) }
        val text = runCatching {
            val dec = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            dec.decode(java.nio.ByteBuffer.wrap(head, 0, (head.size - 3).coerceAtLeast(0))).toString()
        }.getOrNull()
        if (text != null && head.isNotEmpty() && text.count { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' } * 100 <= text.length * 5) return String(head, Charsets.UTF_8)
        return head.take(hexBytes).chunked(16).mapIndexed { i, row ->
            "%06x  ".format(i * 16) + row.joinToString(" ") { "%02x".format(it) } + "  " + row.map { b -> if (b.toInt() in 32..126) b.toInt().toChar() else '.' }.joinToString("")
        }.joinToString("\n") + "\n\n(binary file, ${file.length()} bytes)"
    }

    private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
        val buf = ByteArray(n); var off = 0
        while (off < n) { val r = read(buf, off, n - off); if (r < 0) break; off += r }
        return buf.copyOf(off)
    }
}
