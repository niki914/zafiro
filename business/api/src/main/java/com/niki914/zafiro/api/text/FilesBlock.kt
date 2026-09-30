package com.niki914.zafiro.api.text

import com.niki914.zafiro.api.model.FileRef

/**
 * `<zfr-files>` 块的载荷规则。
 *
 * ```
 * <zfr-files>
 * The user attached these paths:
 * - file: /storage/emulated/0/Download/report.pdf
 * - folder: /storage/emulated/0/adbi/
 * </zfr-files>
 * ```
 *
 * - 容器内**带 `file:` / `folder:` 前缀的 `- ` 行**才是条目，其余行（给模型看的说明）忽略。
 *   前缀让说明行里即便出现列表也不会被误当路径。
 * - 前缀本身已经说明它是不是目录；末尾的 `/` 也照样带着，路径字符串仍然是唯一真源。
 * - 目录路径在入口处就已经带上末尾 `/`（`DocumentPathResolver` 对 tree uri 补的），本类不做归一化。
 *
 * 这是「注入内容」的 producer 之一，不是机制本身：机制在 [TurnTextComposer]，
 * 它不知道有文件这回事。
 */
object FilesBlock {

    /** 块 tag。完整形态是 `<zfr-files>`。 */
    const val TAG = "files"

    /** 给模型看的说明行。它是 prompt 的一部分，不本地化。 */
    private const val EXPLANATION = "The user attached these paths:"

    private const val BULLET = "- "
    private const val FILE_PREFIX = "file:"
    private const val FOLDER_PREFIX = "folder:"

    /** 空列表返回 null（不拼空块）。 */
    fun block(files: List<FileRef>): String? {
        if (files.isEmpty()) return null
        val body = buildString {
            append(EXPLANATION)
            files.forEach { file ->
                val prefix = if (file.path.endsWith('/')) FOLDER_PREFIX else FILE_PREFIX
                append('\n').append(BULLET).append(prefix).append(' ').append(file.path)
            }
        }
        return TurnTextComposer.wrapBlock(TAG, body)
    }

    /** 载荷 → 文件引用。只认带 [FILE_PREFIX] / [FOLDER_PREFIX] 的条目行；两侧空白去掉。 */
    fun parse(body: String): List<FileRef> = body.lineSequence()
        .mapNotNull { line ->
            val entry = line
                .takeIf { it.startsWith(BULLET) }
                ?.removePrefix(BULLET)
                ?.trim()
                ?: return@mapNotNull null
            val path = when {
                entry.startsWith(FOLDER_PREFIX) -> entry.removePrefix(FOLDER_PREFIX)
                entry.startsWith(FILE_PREFIX) -> entry.removePrefix(FILE_PREFIX)
                else -> return@mapNotNull null
            }.trim()
            path.takeIf { it.isNotEmpty() }?.let(::FileRef)
        }
        .toList()

    /** [strip] 的结果：干净的用户文本 + 解回来的文件引用。 */
    data class Stripped(val text: String, val files: List<FileRef>)

    /**
     * 展示 / 回填路径上的**统一入口**：切掉头部的注入块，并把里面的 files 解回来。
     *
     * 三个消费点（历史装配、fork 回填、预览）都走这里，免得路径规则分叉。
     * 实时路径不经过本方法：它本来就是干净的（拼装在 `LLMController` 里）。
     */
    fun strip(text: String): Stripped {
        val cut = TurnTextComposer.cutLeadingBlocks(text)
        return Stripped(text = cut.text, files = of(cut.blocks))
    }

    /** 从切下来的块里解出文件引用：认第一个 files 块，没有就是空。 */
    fun of(blocks: List<TurnTextComposer.Block>): List<FileRef> =
        blocks.firstOrNull { it.tag == TAG }?.let { parse(it.body) }.orEmpty()
}
