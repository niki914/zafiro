package com.niki914.zafiro.util

/**
 * 工具输出统一截断器，对齐 pi 的 truncateHead / truncateTail 语义：
 * - 行数 / 字节双限制（2000 行 / 50KB）先到先生效；
 * - head 永不截断完整行；tail 仅在首个输出行超字节限制时允许部分行；
 * - 字节计数为 UTF-8，截断边界不回退到半个字符。
 * 调用方负责在 [Truncation.truncated] 时附截断提示。
 */
object ToolOutputTruncator {

    const val DEFAULT_MAX_LINES = 2000
    const val DEFAULT_MAX_BYTES = 50 * 1024

    data class Truncation(
        val content: String,
        val truncated: Boolean,
        val totalLines: Int,
        val totalBytes: Int,
    )

    /** 保留开头。适合文档 / 文件类内容（如 SKILL.md）。 */
    fun truncateHead(
        content: String,
        maxLines: Int = DEFAULT_MAX_LINES,
        maxBytes: Int = DEFAULT_MAX_BYTES,
    ): Truncation {
        val lines = content.split("\n")
        val totalBytes = content.toByteArray(Charsets.UTF_8).size
        if (lines.size <= maxLines && totalBytes <= maxBytes) {
            return Truncation(content, truncated = false, lines.size, totalBytes)
        }
        val kept = mutableListOf<String>()
        var keptBytes = 0
        for ((index, line) in lines.withIndex()) {
            if (kept.size >= maxLines) break
            val lineBytes = line.toByteArray(Charsets.UTF_8).size + if (index > 0) 1 else 0
            if (keptBytes + lineBytes > maxBytes) break
            kept += line
            keptBytes += lineBytes
        }
        return Truncation(kept.joinToString("\n"), truncated = true, lines.size, totalBytes)
    }

    /** 保留结尾。适合命令执行类内容（结果 / 报错在末尾）。 */
    fun truncateTail(
        content: String,
        maxLines: Int = DEFAULT_MAX_LINES,
        maxBytes: Int = DEFAULT_MAX_BYTES,
    ): Truncation {
        val lines = content.split("\n")
        val totalBytes = content.toByteArray(Charsets.UTF_8).size
        if (lines.size <= maxLines && totalBytes <= maxBytes) {
            return Truncation(content, truncated = false, lines.size, totalBytes)
        }
        val kept = ArrayDeque<String>()
        var keptBytes = 0
        for (index in lines.indices.reversed()) {
            if (kept.size >= maxLines) break
            val lineBytes =
                lines[index].toByteArray(Charsets.UTF_8).size + if (kept.isNotEmpty()) 1 else 0
            if (keptBytes + lineBytes > maxBytes) {
                // 一个行都放不下时取该行尾部（UTF-8 安全，允许部分行）
                if (kept.isEmpty()) {
                    val partial = takeLastUtf8(lines[index], maxBytes)
                    kept.addFirst(partial)
                    keptBytes = partial.toByteArray(Charsets.UTF_8).size
                }
                break
            }
            kept.addFirst(lines[index])
            keptBytes += lineBytes
        }
        return Truncation(kept.joinToString("\n"), truncated = true, lines.size, totalBytes)
    }

    private fun takeLastUtf8(text: String, maxBytes: Int): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return text
        var start = bytes.size - maxBytes
        while (start < bytes.size && (bytes[start].toInt() and 0xC0) == 0x80) start++
        return String(bytes, start, bytes.size - start, Charsets.UTF_8)
    }
}
