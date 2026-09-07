package com.niki914.zafiro.util

import com.niki914.logging.Logger
import java.io.File
import kotlin.random.Random

/**
 * 工具输出统一截断器，对齐 pi 的 truncateHead / truncateTail 语义：
 * - 行数 / 字节双限制（2000 行 / 50KB）先到先生效；
 * - head 永不截断完整行；tail 仅在首个输出行超字节限制时允许部分行；
 * - 字节计数为 UTF-8，截断边界不回退到半个字符。
 * 调用方负责在 [Truncation.truncated] 时附截断提示。
 */
object ToolOutputTruncator {

    private const val LOG_TAG = "niki914_nexus_ToolOutputTruncator"

    /** 截断导出目录（App 沙箱 filesDir 下），agent 可用 terminal 回读全量。 */
    const val EXPORT_DIR_NAME = "tool_output"

    /**
     * 生产默认导出目录：filesDir/tool_output。无 Context（单测）时返回 null
     * （不导出），调用方无需各自接 ContextProvider。
     */
    fun defaultExportDir(): File? {
        val context = com.niki914.xposed.api.util.ContextProvider.awaitIfAvailable() ?: return null
        return File(context.filesDir, EXPORT_DIR_NAME)
    }

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

    /**
     * 统一入口：过滤全文，超限时导出全量文件并在提示中附绝对路径（对齐 pi）。
     *
     * @param fullContent 工具完整输出
     * @param existingFile 输出已有落盘文件（如 Python 传输文件）：截断时直接
     *   move 到导出目录（零拷贝）；null 时现场写入导出文件
     * @param exportDir 导出目录（filesDir/tool_output），null 表示不导出（仅截断）
     * @return 截断后内容（超限时末尾附 Full output 路径提示）
     */
    fun filterForAgent(
        fullContent: String,
        existingFile: File? = null,
        exportDir: File? = null,
    ): String {
        val truncation = truncateTail(fullContent)
        if (!truncation.truncated) {
            // 传输文件消费完即删（未截断无需导出）
            existingFile?.takeIf { it.exists() }?.delete()
            return fullContent
        }
        val exportPath = exportTo(fullContent, existingFile, exportDir)
        return buildString {
            append(truncation.content)
            append("\n\n[Output truncated: showing last ")
            append(truncation.content.count { it == '\n' })
            append(" of ")
            append(truncation.totalLines)
            append(" lines]")
            if (exportPath != null) {
                append("\n[Full output: ")
                append(exportPath)
                append("]")
            }
        }
    }

    /** 把全量输出落到导出目录：优先 move 已有文件（零拷贝），否则现写。返回绝对路径。 */
    private fun exportTo(fullContent: String, existingFile: File?, exportDir: File?): String? {
        if (exportDir == null) return null
        return try {
            exportDir.mkdirs()
            val target = File(exportDir, exportFileName())
            if (existingFile != null && existingFile.exists()) {
                if (!existingFile.renameTo(target)) {
                    // 跨目录 rename 失败（罕见）：降级复制 + 删源
                    existingFile.copyTo(target, overwrite = true)
                    existingFile.delete()
                }
            } else {
                target.writeText(fullContent, Charsets.UTF_8)
            }
            Logger.d(LOG_TAG, "tool output exported bytes=${target.length()} path=${target.absolutePath}")
            target.absolutePath
        } catch (e: Exception) {
            // 导出失败不阻断主链路：截断提示照常返回，只是没有全量文件可回读
            Logger.w(LOG_TAG, "tool output export failed: ${e.message}")
            null
        }
    }

    private fun exportFileName(): String =
        (Random.nextBits(32).toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0') + ".log"
}
