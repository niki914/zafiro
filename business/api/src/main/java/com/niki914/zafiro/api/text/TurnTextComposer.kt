package com.niki914.zafiro.api.text

/**
 * 注入块与用户正文之间的编解码。
 *
 * ## 形态
 *
 * 一行开标签、若干行载荷、一行闭标签：
 *
 * ```
 * <zfr-files>
 * The user attached these paths:
 * - file: /storage/emulated/0/Download/report.pdf
 * </zfr-files>
 * ```
 *
 * ## 为什么是这种极简语法
 *
 * - 边界行**可判定**（必须独占一行、必须成对），因此不需要真正的 XML 解析器，
 *   也就不用处理 `<` `&` `"` 转义那种白送的复杂度。
 * - 载荷里只有「接机器读的行」（`- ` 开头）与「给模型看的说明行」，后者原样留着。
 * - 路径不可能含换行，所以**不需要任何转义**；同理，载荷里写不出闭标签。
 * - 用户手打的同名容器如果恰好构成完整结构，会被当成引用——接受，不额外防御（D7）。
 *
 * ## 只能从开头切
 *
 * 服务端（往提示词里拼）与消费端（从落盘文本里解回来）都假定块在**文本开头**：
 * 用户真正的那句话必须落在 prompt 的最后，以保持服从度（D8）。
 * 这也是为什么本类只有 [cutLeadingBlocks]，没有「切尾部」的兄弟方法。
 *
 * ⚠️ 与「不带 tag 的瞬态通知」的耦合：通知（MCP 失败说明 / 终端完成通知）也拼在
 * 用户文本前面，但它们不带 tag。本方法撞上第一行非 tag 文本就停手，所以
 * **带 tag 的块必须排在通知之前**才会被切掉（`LLMController` 里有注释）。
 * 通知将来也走这条链路（见那里的 TODO）之后，这个顺序约束就消失了。
 */
object TurnTextComposer {

    /** tag 前缀。需求方定的是 `zfr-`，不是 `zafiro-`。 */
    const val TAG_PREFIX = "zfr-"

    /** 一个被切下来的注入块。 */
    data class Block(val tag: String, val body: String)

    /** 切头的结果：[text] 是剩下的人话，[blocks] 是被摘掉的块（按出现顺序）。 */
    data class Cut(val text: String, val blocks: List<Block>)

    /** 把载荷包成块。tag 传 `files`（不带前缀）。 */
    fun wrapBlock(tag: String, body: String): String =
        "<$TAG_PREFIX$tag>\n$body\n</$TAG_PREFIX$tag>"

    /**
     * 反复吃掉开头的注入块，返回剩下的文本与被摘掉的块。
     *
     * 只认**完整**的块：开标签独占第一行、且后面能找到独占一行的配对闭标签。
     * 找不到闭标签就当这段文本是用户自己写的，整段原样返回（不做任何猜测）。
     * 一个块都没切到时返回的 [Cut.text] 与入参**逐字节相同**。
     */
    fun cutLeadingBlocks(text: String): Cut {
        if (text.isEmpty()) return Cut(text, emptyList())
        val lines = text.split('\n')
        val blocks = mutableListOf<Block>()
        var index = 0
        while (index < lines.size) {
            val tag = openTagOrNull(lines[index]) ?: break
            val close = "</$TAG_PREFIX$tag>"
            val closeIndex = (index + 1 until lines.size).firstOrNull { lines[it] == close } ?: break
            blocks += Block(tag = tag, body = lines.subList(index + 1, closeIndex).joinToString("\n"))
            index = closeIndex + 1
            // 块与正文之间的那个空行是拼装时加的，一起吃掉
            if (index < lines.size && lines[index].isBlank()) index++
        }
        if (blocks.isEmpty()) return Cut(text, emptyList())
        return Cut(lines.subList(index, lines.size).joinToString("\n"), blocks)
    }

    /** `<zfr-files>` → `files`；不是独占一行的合法开标签则返回 null。 */
    private fun openTagOrNull(line: String): String? {
        if (!line.startsWith("<$TAG_PREFIX") || !line.endsWith(">")) return null
        val tag = line.substring(TAG_PREFIX.length + 1, line.length - 1)
        if (tag.isEmpty() || !tag.all(::isTagChar)) return null
        return tag
    }

    private fun isTagChar(char: Char): Boolean =
        char.isLetterOrDigit() || char == '-' || char == '_'
}
