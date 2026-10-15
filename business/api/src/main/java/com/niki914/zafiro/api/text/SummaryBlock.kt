package com.niki914.zafiro.api.text

/**
 * `<zfr-summary>` 块的载荷规则。
 *
 * 形态：
 * ```
 * <zfr-summary>
 * keep-turns: 2
 *
 * The conversation history before this point was compacted into the following summary:
 * <summary>
 * ## Goal
 * ...
 * </summary>
 * </zfr-summary>
 * ```
 *
 * 遵循 Pi 的命名与提示范式（以“摘要”视角与模型通信）：
 * - 外层为 [TurnTextComposer] 约定的 `<zfr-summary>` 注入块；
 * - 块内首行声明元数据 `keep-turns: <Int>`（指示压缩点前保留多少个活跃用户轮次）；
 * - 空行后为提示文案与用 `<summary>` 标签包裹的 Markdown 摘要正文。
 */
object SummaryBlock {

    /** 块 tag。完整形态是 `<zfr-summary>`。 */
    const val TAG = "summary"

    /** 元数据行前缀。 */
    const val PREFIX_KEEP_TURNS = "keep-turns:"

    /** 摘要正文包裹开标签。 */
    const val TAG_SUMMARY_OPEN = "<summary>"

    /** 摘要正文包裹闭标签。 */
    const val TAG_SUMMARY_CLOSE = "</summary>"

    /** 给模型的提示文案，不本地化。 */
    const val PROMPT_INTRO = "The conversation history before this point was compacted into the following summary:"

    /**
     * 压缩载荷数据对象。
     *
     * @property keepTurns 在压缩点之前需保留的活跃用户轮次数量（>= 0）。
     * @property summary 压缩生成的 Markdown 结构化摘要正文。
     */
    data class Payload(
        val keepTurns: Int,
        val summary: String,
    )

    /**
     * 把结构体组装为合法注入块文本。
     */
    fun block(payload: Payload): String {
        require(payload.keepTurns >= 0) { "keepTurns must be non-negative: ${payload.keepTurns}" }
        val body = buildString {
            append(PREFIX_KEEP_TURNS).append(' ').append(payload.keepTurns).append("\n\n")
            append(PROMPT_INTRO).append('\n')
            append(TAG_SUMMARY_OPEN).append('\n')
            append(payload.summary.trim())
            append('\n').append(TAG_SUMMARY_CLOSE)
        }
        return TurnTextComposer.wrapBlock(TAG, body)
    }

    /**
     * 从块内部 [body] 中解析元数据与摘要。遇格式错误防御性返回 `null`，严禁抛出异常。
     */
    fun parse(body: String): Payload? {
        val lines = body.lines()
        val keepTurnsLine = lines.firstOrNull { it.startsWith(PREFIX_KEEP_TURNS) } ?: return null
        val keepTurns = keepTurnsLine.removePrefix(PREFIX_KEEP_TURNS).trim().toIntOrNull() ?: return null
        if (keepTurns < 0) return null

        val startSummary = lines.indexOfFirst { it.trim() == TAG_SUMMARY_OPEN }
        val endSummary = lines.indexOfLast { it.trim() == TAG_SUMMARY_CLOSE }
        if (startSummary == -1 || endSummary == -1 || endSummary <= startSummary) return null

        val summary = lines.subList(startSummary + 1, endSummary).joinToString("\n").trim()
        return Payload(keepTurns = keepTurns, summary = summary)
    }

    /** [strip] 的结果：干净的用户文本 + 解回来的压缩载荷。 */
    data class Stripped(val text: String, val payload: Payload?)

    /**
     * 切掉头部的注入块，并解析其中的压缩载荷。
     */
    fun strip(text: String): Stripped {
        val cut = TurnTextComposer.cutLeadingBlocks(text)
        return Stripped(text = cut.text, payload = of(cut.blocks))
    }

    /**
     * 从切下来的块列表里解出载荷：认第一个 [TAG] 块，没有或解析失败返回 null。
     */
    fun of(blocks: List<TurnTextComposer.Block>): Payload? =
        blocks.firstOrNull { it.tag == TAG }?.let { parse(it.body) }

    /**
     * 轻量判定文本头部是否含有该注入块且格式合法。
     */
    fun hasBlock(text: String): Boolean = strip(text).payload != null
}
